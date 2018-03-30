package nz.sodium;

import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class SodiumCtx {
    private final AtomicInteger nextId = new AtomicInteger(0);
    private int transactionDepth = 0;
    private java.util.PriorityQueue<Node> priorityQ = new java.util.PriorityQueue<>((Node a, Node b) -> a.rank < b.rank ? -1 : (a.rank > b.rank ? +1 : Integer.compare(a.id, b.id)));
    private java.util.HashSet<Node> entries = new java.util.HashSet<>();
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private java.util.ArrayList<Listener> keepAlive = new java.util.ArrayList<>();
    private java.util.ArrayList<Runnable> lastQ = new java.util.ArrayList<>();

    public SodiumCtx() {}

    public Node mkNode() {
        return new Node();
    }

    public Node mkNode(HasNode[] dependencies, Supplier<Boolean> update) {
        return new Node(dependencies, update);
    }

    public <A> CellSink<A> mkCellSink(A value) {
        return new CellSink<>(value);
    }

    public <A> StreamSink<A> mkStreamSink() {
        return new StreamSink<>();
    }

    private void prioritize(Node node) {
        if (!entries.contains(node)) {
            priorityQ.add(node);
            entries.add(node);
        }
    }

    public class Listener {
        private Node _node;

        private Listener(Node node) {
            this._node = node;
            keepAlive.add(this);
        }

        public void unlisten() {
            if (_node != null) {
                this._node.doCleanups();
                this._node = null;
                keepAlive.remove(this);
            }
        }
    }

    public class CellSink<A> {
        private Node _node;
        private A _value;
        private Cell<A> _cell;

        public CellSink(A value) {
            this._node = new Node();
            this._value = value;
            this._cell = new Cell<>(
                _node,
                () -> this._value
            );
        }

        public void send(A value) {
            transactionVoid(() -> {
                this._value = value;
                this._node.changed();
            });
        }

        public Cell<A> cell() {
            return _cell;
        }
    }

    public class StreamSink<A> {
        private Node _node;
        private Optional<A> _value;
        private boolean _valueWillReset = false;
        private Stream<A> _stream;

        public StreamSink() {
            this._node = new Node();
            this._value = Optional.empty();
            this._stream = new Stream<>(
                _node,
                () -> this._value
            );
        }

        public void send(A value) {
            transactionVoid(() -> {
                this._value = Optional.of(value);
                if (!this._valueWillReset) {
                    this._valueWillReset = true;
                    last(() -> {
                        this._value = Optional.empty();
                        this._valueWillReset = false;
                    });
                }
                this._node.changed();
            });
        }

        public Stream<A> stream() {
            return _stream;
        }
    }

    public class Stream<A> implements HasNode {
        private Node _node;
        private Supplier<Optional<A>> _valueSupplier;

        public Stream(Node node, Supplier<Optional<A>> valueSupplier) {
            this._node = node;
            this._valueSupplier = valueSupplier;
        }

        public <B> Stream<B> map(Lambda1<A,B> f) {
            LazyCache<Optional<B>> valueLazyCache = new LazyCache<>(() -> this._valueSupplier.get().map(f::apply));
            return new Stream<>(
                new Node(
                    new HasNode[] { this },
                    () -> {
                        valueLazyCache.resetCache();
                        return true;
                    }
                ),
                valueLazyCache::value
            );
        }

        public Stream<A> filter(Predicate<A> condition) {
            LazyCache<Optional<A>> valueLazyCache = new LazyCache<>(() -> this._valueSupplier.get().filter(condition));
            return new Stream<>(
                new Node(
                    new HasNode[] { this },
                    () -> {
                        Optional<A> before = valueLazyCache.value();
                        valueLazyCache.resetCache();
                        Optional<A> after = valueLazyCache.value();
                        return after.isPresent() || before.isPresent();
                    }
                ),
                valueLazyCache::value
            );
        }

        public Stream<A> merge(Stream<A> s, Lambda2<A,A,A> f) {
            LazyCache<Optional<A>> valueLazyCache = new LazyCache<>(() -> {
                Optional<A> leftOp = this._valueSupplier.get();
                Optional<A> rightOp = s._valueSupplier.get();
                if (leftOp.isPresent()) {
                    A left = leftOp.get();
                    if (rightOp.isPresent()) {
                        A right = rightOp.get();
                        return Optional.of(f.apply(left, right));
                    } else {
                        return Optional.of(left);
                    }
                } else {
                    return rightOp;
                }
            });
            return new Stream<>(
                new Node(
                    new HasNode[] { this, s },
                    () -> {
                        valueLazyCache.resetCache();
                        return true;
                    }
                ),
                valueLazyCache::value
            );
        }

        @Override
        public Node node() {
            return _node;
        }

        @Override
        public void dummy() {
        }

        public Listener listen(Consumer<A> action) {
            return new Listener(
                new Node(
                    new HasNode[] { this },
                    () -> {
                        this._valueSupplier.get().ifPresent(action);
                        return false;
                    }
                )
            );
        }
    }

    public class Cell<A> implements HasNode {
        private Node _node;
        private Supplier<A> _valueSupplier;

        public Cell(Node node, Supplier<A> valueSupplier) {
            this._node = node;
            this._valueSupplier = valueSupplier;
        }

        public <B> Cell<B> map(Lambda1<A,B> f) {
            LazyCache<B> valueLazyCache = new LazyCache<>(() -> f.apply(this.value()));
            return new Cell<>(
                new Node(
                    new HasNode[] { this },
                    () -> {
                        valueLazyCache.resetCache();
                        return true;
                    }
                ),
                valueLazyCache::value
            );
        }

        public <B,C> Cell<C> lift(Cell<B> cb, Lambda2<A,B,C> f) {
            LazyCache<C> valueLazyCache = new LazyCache<>(() -> f.apply(this.value(), cb.value()));
            return new Cell<>(
                new Node(
                    new HasNode[] { this, cb },
                    () -> {
                        valueLazyCache.resetCache();
                        return true;
                    }
                ),
                valueLazyCache::value
            );
        }

        public <B,C,D> Cell<D> lift(Cell<B> cb, Cell<C> cc, Lambda3<A,B,C,D> f) {
            LazyCache<D> valueLazyCache = new LazyCache<>(() -> f.apply(this.value(), cb.value(), cc.value()));
            return new Cell<>(
                new Node(
                    new HasNode[] { this, cb, cc },
                    () -> {
                        valueLazyCache.resetCache();
                        return true;
                    }
                ),
                valueLazyCache::value
            );
        }

        public <B,C,D,E> Cell<E> lift(Cell<B> cb, Cell<C> cc, Cell<D> cd, Lambda4<A,B,C,D,E> f) {
            LazyCache<E> valueLazyCache = new LazyCache<>(() -> f.apply(this.value(), cb.value(), cc.value(), cd.value()));
            return new Cell<>(
                new Node(
                    new HasNode[] { this, cb, cc, cd },
                    () -> {
                        valueLazyCache.resetCache();
                        return true;
                    }
                ),
                valueLazyCache::value
            );
        }

        public <B,C,D,E,F_> Cell<F_> lift(Cell<B> cb, Cell<C> cc, Cell<D> cd, Cell<E> ce, Lambda5<A,B,C,D,E,F_> f) {
            LazyCache<F_> valueLazyCache = new LazyCache<>(() -> f.apply(this.value(), cb.value(), cc.value(), cd.value(), ce.value()));
            return new Cell<>(
                new Node(
                    new HasNode[] { this, cb, cc, cd },
                    () -> {
                        valueLazyCache.resetCache();
                        return true;
                    }
                ),
                valueLazyCache::value
            );
        }

        public <B,C,D,E,F_,G> Cell<G> lift(Cell<B> cb, Cell<C> cc, Cell<D> cd, Cell<E> ce, Cell<F_> cf, Lambda6<A,B,C,D,E,F_,G> f) {
            LazyCache<G> valueLazyCache = new LazyCache<>(() -> f.apply(this.value(), cb.value(), cc.value(), cd.value(), ce.value(), cf.value()));
            return new Cell<>(
                new Node(
                    new HasNode[] { this, cb, cc, cd, ce },
                    () -> {
                        valueLazyCache.resetCache();
                        return true;
                    }
                ),
                valueLazyCache::value
            );
        }

        public Listener listen(Consumer<A> action) {
            action.accept(this.value());
            return new Listener(
                new Node(
                    new HasNode[] { this },
                    () -> {
                        action.accept(this.value());
                        return false;
                    }
                )
            );
        }

        @Override
        public Node node() {
            return _node;
        }

        @Override
        public void dummy() {
        }

        public Supplier<A> valueSupplier() {
            return _valueSupplier;
        }

        public A value() {
            return _valueSupplier.get();
        }
    }

    private class LazyCache<A> {
        private Supplier<A> valueSupplier;
        private Optional<A> valueCache = Optional.empty();
        private LazyCache(Supplier<A> valueSupplier) {
            this.valueSupplier = valueSupplier;
        }
        void resetCache() {
            valueCache = Optional.empty();
        }
        A value() {
            if (valueCache.isPresent()) {
                return valueCache.get();
            }
            A value = valueSupplier.get();
            valueCache = Optional.of(value);
            return value;
        }
    }

    public interface HasNode {
        Node node();

        // IntelliJ inspection performance bug workaround.
        void dummy();
    }

    public class Node implements HasNode {
        private final int id;
        private int rank;
        private Supplier<Boolean> update;
        private final java.util.List<WeakReference<Node>> targets;
        private final java.util.List<Runnable> cleanups = new java.util.ArrayList<>();
        public Node() {
            this(new HasNode[] {}, () -> false);
        }
        public Node(HasNode[] dependencies, Supplier<Boolean> update) {
            this.id = nextId.getAndIncrement();
            this.rank = calcRankFromDependencies(dependencies);
            this.update = update;
            this.targets = new java.util.ArrayList<>();
            for (HasNode dependency : dependencies) {
                dependency.node().targets.add(new WeakReference<>(this));
            }
            this.cleanups.add(() -> {
                for (HasNode dependence : dependencies) {
                    for (int i = dependence.node().targets.size()-1; i >= 0; --i) {
                        WeakReference<Node> target = dependence.node().targets.get(i);
                        Node target2 = target.get();
                        if (target2 == null || target2 == this) {
                            dependence.node().targets.remove(i);
                        }
                    }
                }
            });
        }
        @Override
        protected void finalize() throws Throwable {
            doCleanups();
        }
        private void doCleanups() {
            for (Runnable cleanup : cleanups) {
                cleanup.run();
            }
            cleanups.clear();
        }
        @Override
        public Node node() {
            return this;
        }
        @Override
        public void dummy() {
        }
        private int calcRankFromDependencies(HasNode[] dependencies) {
            int rank = 0;
            for (HasNode dependsOn : dependencies) {
                if (dependsOn.node().rank >= rank) {
                    rank = Math.max(rank, dependsOn.node().rank + 1);
                }
            }
            return rank;
        }
        private boolean update() {
            return update.get();
        }
        private void changed() {
            transactionVoid(() -> {
                for (WeakReference<Node> child : targets) {
                    Node child2 = child.get();
                    if (child2 != null) {
                        prioritize(child2);
                    }
                }
            });
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(id) ^ Integer.hashCode(rank);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof Node)) {
                return false;
            }
            Node other = (Node)obj;
            return id == other.id && rank == other.rank;
        }
    }

    public <A> A transaction(Supplier<A> code) {
        try {
            ++transactionDepth;
            return code.get();
        } finally {
            --transactionDepth;
            if (transactionDepth == 0) {
                update();
            }
        }
    }

    public void transactionVoid(Runnable code) {
        try {
            ++transactionDepth;
            code.run();
        } finally {
            --transactionDepth;
            if (transactionDepth == 0) {
                update();
            }
        }
    }

    public void last(Runnable last) {
        lastQ.add(last);
    }

    private void update() {
        while (!priorityQ.isEmpty()) {
            Node node = priorityQ.poll();
            entries.remove(node);
            boolean changed = node.update();
            if (changed) {
                for (WeakReference<Node> child : node.targets) {
                    Node child2 = child.get();
                    if (child2 != null) {
                        prioritize(child2);
                    }
                }
            }
        }
        Runnable[] lastQ2 = lastQ.toArray(new Runnable[lastQ.size()]);
        lastQ.clear();
        for (Runnable last : lastQ2) {
            last.run();
        }
    }

    private static void testNode() {
        System.out.println("Node test");
        SodiumCtx sodiumCtx = new SodiumCtx();
        class Util {
            private int a;
            private int b;
            private Node aNode = sodiumCtx.mkNode();
            void setA(int a) {
                this.a = a;
                aNode.changed();
            }

        }
        final Util util = new Util();
        {
            Node bNode = sodiumCtx.mkNode(new Node[]{util.aNode}, () -> {
                util.b = util.a * 2;
                return true;
            });
            Node displayNode = sodiumCtx.mkNode(
                new HasNode[]{util.aNode, bNode},
                () -> {
                    System.out.println(String.format("a = %d, b = %d", util.a, util.b));
                    return false;
                }
            );
            util.setA(1);
            util.setA(2);
            bNode.doCleanups();
            displayNode.doCleanups();
        }
        util.setA(3);
        System.out.println();
    }

    private static void testCell() {
        System.out.println("Cell test");
        SodiumCtx sodiumCtx = new SodiumCtx();
        CellSink<Integer> csa = sodiumCtx.mkCellSink(1);
        Cell<Integer> ca = csa.cell();
        Cell<Integer> cb = ca.map((Integer a) -> a * 2);
        Listener l = ca.lift(cb, Tuple::of).listen((Tuple<Integer,Integer> ab) -> {
            int a = ab._1();
            int b = ab._2();
            System.out.println(String.format("a = %d, b = %d", a, b));
        });
        csa.send(2);
        csa.send(3);
        l.unlisten();
        System.out.println();
    }

    private static void testStream() {
        System.out.println("Stream test");
        SodiumCtx sodiumCtx = new SodiumCtx();
        StreamSink<Integer> ssa = sodiumCtx.mkStreamSink();
        Stream<Integer> sa = ssa.stream();
        Stream<Integer> sb = sa.map((Integer a) -> a * 2);
        Listener l =
            sa.map((Integer a) -> String.format("a = %d", a))
                .merge(
                    sb.map((Integer b) -> String.format("b = %d", b)),
                    (String a, String b) ->
                        a + ", " + b
                )
                .listen(System.out::println);
        ssa.send(1);
        ssa.send(2);
        ssa.send(3);
        l.unlisten();
        System.out.println();
    }

    public static void main(String[] params) {
        testNode();
        testCell();
        testStream();
    }
}
