package com.zifang.z.indexer.pipeline;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 免编译静态 Java 索引器（JavaParser）：mvn 编译链路失败时的双轨兜底。
 * <p>产出与 code-indexer.jar（javac annotation processor）同 schema 的 jsonl：
 * <pre>
 *   {"t":"def","kind":"class"|"method","sym":"...","file":"...","line":N}
 *   {"t":"call","from":"...","to":"...","file":"...","line":N}
 * </pre>
 * sym 规约对齐编译期产物：类 = 包名.类名（嵌套用 . 连接）；方法 = OwnerFqn#name(参数FQN,...)；
 * 构造器 name = {@code <init>}；参数为全限定名（import 解析），泛型带完整实参，数组 T[]，varargs 按 T[]。
 * <p>静态解析相对编译期的已知局限（schema 完全兼容，精度略降）：
 * <ul>
 *   <li>参数类型 FQN 靠 import 推断（同包 / java.lang / 通配 import 兜底，JDK 常用类按 wildcard 包前缀补全），
 *       推断失败保留短名；带 TYPE_USE 语义注解的参数渲染为 {@code (@A :: T)}（对齐 javac8 AnnotatedType）；</li>
 *   <li>调用边 to 按「声明处签名」仲裁（名字 + 参数个数），无接收者线索时退化为全局唯一同名，
 *       再歧义则丢边（宁缺勿错）；</li>
 *   <li>外部依赖调用 to 无参数签名（如 {@code com.foo.Api#bar}），仅供展示（编译期同样无 def 可回查）；</li>
 *   <li>接口分派边挂在声明（接口/父类）侧，与编译期 Trees.getElement 行为一致；实现类侧边不做。</li>
 * </ul>
 * 调用边在 def 全部注册后统一仲裁写出（跨文件引用需要全局类表）。
 */
public class StaticJavaIndexer {

    /** java.lang 隐式导入的常用类型（编译期 asType() 给全名，静态侧靠此表对齐）。 */
    private static final Set<String> JAVA_LANG = new HashSet<String>(Arrays.asList(
            "Object", "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "CharSequence", "StringBuilder", "StringBuffer", "Number", "Math", "System",
            "Enum", "Exception", "RuntimeException", "Throwable", "Error", "Iterable", "Comparable",
            "Runnable", "Thread", "Class", "Void", "Cloneable", "Appendable", "AutoCloseable",
            "Package", "Process", "ProcessBuilder", "Runtime", "ThreadLocal", "StackTraceElement",
            "IllegalArgumentException", "IllegalStateException", "NullPointerException",
            "IndexOutOfBoundsException", "UnsupportedOperationException", "ClassCastException",
            "NumberFormatException", "ArithmeticException", "ArrayStoreException",
            "ClassNotFoundException", "InterruptedException", "SecurityException",
            "ReflectiveOperationException", "InstantiationException", "IllegalAccessException",
            "NoSuchFieldException", "NoSuchMethodException", "Deprecated", "SuppressWarnings",
            "Override", "FunctionalInterface", "SafeVarargs", "CloneNotSupportedException",
            "AssertionError", "ExceptionInInitializerError", "StackOverflowError",
            "OutOfMemoryError", "NoClassDefFoundError", "LinkageError", "NegativeArraySizeException"));

    /** 参数绑定/序列化注解（@Target 无 TYPE_USE，javac 不渲染进参数类型）：跳过。 */
    private static final Set<String> BINDING_ANNOTATIONS = new HashSet<String>(Arrays.asList(
            "RequestBody", "RequestParam", "PathVariable", "RequestHeader", "CookieValue",
            "ModelAttribute", "RequestPart", "SessionAttribute", "RequestAttribute", "Validated",
            "DateTimeFormat", "NumberFormat", "Param", "JsonProperty", "JsonFormat", "JsonIgnore",
            "JsonIgnoreProperties", "JsonAlias", "JsonUnwrapped", "JsonSetter", "JsonCreator",
            "JsonValue", "JsonInclude", "JsonDeserialize", "JsonSerialize", "ApiParam",
            "MappingTarget", "Context")); // MapStruct 参数注解同无 TYPE_USE，javac 不渲染

    /** JDK 常用类（简名 → FQN）：wildcard import（import java.util.* 等）命中时按包前缀补全（编译期 asType() 给全名）。 */
    private static final Map<String, String> JDK_COMMON_TYPES = new HashMap<String, String>();
    static {
        jdk("java.util", "List", "Map", "Set", "Collection", "Iterator", "ListIterator", "Enumeration",
                "ArrayList", "LinkedList", "ArrayDeque", "HashMap", "LinkedHashMap", "TreeMap", "Hashtable",
                "HashSet", "LinkedHashSet", "TreeSet", "Vector", "Stack", "Queue", "Deque", "PriorityQueue",
                "NavigableMap", "SortedMap", "NavigableSet", "SortedSet", "AbstractMap", "AbstractList", "AbstractSet",
                "Arrays", "Collections", "Objects", "Optional", "OptionalInt", "OptionalLong", "OptionalDouble",
                "Comparator", "UUID", "Date", "Calendar", "GregorianCalendar", "TimeZone", "Locale", "Currency",
                "Random", "Scanner", "StringJoiner", "BitSet", "Properties", "Base64", "EventObject",
                "NoSuchElementException", "ConcurrentModificationException", "EmptyStackException",
                "DoubleSummaryStatistics", "IntSummaryStatistics", "LongSummaryStatistics", "Spliterator",
                "Spliterators", "StringTokenizer", "IdentityHashMap", "WeakHashMap", "EnumMap", "EnumSet");
        jdk("java.util.function", "Function", "BiFunction", "Consumer", "BiConsumer", "Supplier",
                "Predicate", "BiPredicate", "UnaryOperator", "BinaryOperator", "BooleanSupplier",
                "IntFunction", "ToIntFunction", "ToLongFunction", "ToDoubleFunction", "IntPredicate",
                "LongPredicate", "DoublePredicate", "IntConsumer", "LongConsumer", "DoubleConsumer",
                "IntSupplier", "LongSupplier", "DoubleSupplier", "IntUnaryOperator", "LongUnaryOperator",
                "DoubleUnaryOperator", "IntBinaryOperator", "LongBinaryOperator", "DoubleBinaryOperator",
                "ObjIntConsumer", "ObjLongConsumer", "ObjDoubleConsumer");
        jdk("java.util.stream", "Stream", "IntStream", "LongStream", "DoubleStream", "Collector", "Collectors");
        jdk("java.util.concurrent", "ConcurrentHashMap", "ConcurrentMap", "ConcurrentNavigableMap",
                "ConcurrentSkipListMap", "ConcurrentSkipListSet", "ConcurrentLinkedQueue", "ConcurrentLinkedDeque",
                "CopyOnWriteArrayList", "CopyOnWriteArraySet", "BlockingQueue", "BlockingDeque",
                "LinkedBlockingQueue", "LinkedBlockingDeque", "ArrayBlockingQueue", "PriorityBlockingQueue",
                "SynchronousQueue", "DelayQueue", "LinkedTransferQueue", "TransferQueue", "CountDownLatch",
                "CyclicBarrier", "Semaphore", "Exchanger", "Phaser", "Executor", "ExecutorService", "Executors",
                "ScheduledExecutorService", "ScheduledThreadPoolExecutor", "ThreadPoolExecutor", "ForkJoinPool",
                "ForkJoinTask", "CompletableFuture", "Future", "FutureTask", "TimeUnit", "ThreadLocalRandom",
                "ThreadFactory", "RejectedExecutionException", "ExecutionException", "TimeoutException",
                "CancellationException", "BrokenBarrierException");
        jdk("java.util.concurrent.atomic", "AtomicBoolean", "AtomicInteger", "AtomicLong", "AtomicReference",
                "AtomicIntegerArray", "AtomicLongArray", "AtomicReferenceArray", "LongAdder", "DoubleAdder",
                "LongAccumulator", "DoubleAccumulator");
        jdk("java.util.concurrent.locks", "Lock", "Condition", "ReentrantLock", "ReentrantReadWriteLock",
                "ReadWriteLock", "StampedLock", "LockSupport");
        jdk("java.math", "BigDecimal", "BigInteger", "MathContext", "RoundingMode");
        jdk("java.time", "LocalDate", "LocalTime", "LocalDateTime", "ZonedDateTime", "OffsetDateTime",
                "OffsetTime", "Instant", "Duration", "Period", "ZoneId", "ZoneOffset", "DateTimeFormatter",
                "DateTimeFormatterBuilder", "DayOfWeek", "Month", "MonthDay", "Year", "YearMonth", "Clock",
                "DateTimeException", "DateTimeParseException");
        jdk("java.time.temporal", "Temporal", "TemporalAccessor", "TemporalAdjuster", "TemporalAdjusters",
                "TemporalAmount", "TemporalField", "TemporalUnit", "ChronoUnit", "ChronoField", "ValueRange",
                "WeekFields", "IsoFields");
        jdk("java.io", "Serializable", "Externalizable", "Closeable", "Flushable", "IOException",
                "UncheckedIOException", "FileNotFoundException", "EOFException", "InterruptedIOException",
                "NotSerializableException", "StreamCorruptedException", "InputStream", "OutputStream",
                "Reader", "Writer", "BufferedReader", "BufferedWriter", "InputStreamReader", "OutputStreamWriter",
                "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "ByteArrayInputStream",
                "ByteArrayOutputStream", "StringReader", "StringWriter", "CharArrayReader", "CharArrayWriter",
                "PrintStream", "PrintWriter", "ObjectInputStream", "ObjectOutputStream", "DataInput", "DataOutput",
                "DataInputStream", "DataOutputStream", "File", "FileDescriptor", "FilenameFilter", "FileFilter",
                "RandomAccessFile");
        jdk("java.nio", "Buffer", "ByteBuffer", "CharBuffer", "ShortBuffer", "IntBuffer", "LongBuffer",
                "FloatBuffer", "DoubleBuffer", "ByteOrder", "BufferOverflowException", "BufferUnderflowException",
                "ReadOnlyBufferException");
        jdk("java.nio.charset", "Charset", "StandardCharsets", "CodingErrorAction", "CharacterCodingException",
                "UnsupportedCharsetException", "MalformedInputException", "UnmappableCharacterException");
        jdk("java.nio.file", "Path", "Paths", "Files", "FileSystem", "FileSystems", "StandardOpenOption",
                "StandardCopyOption", "FileVisitResult", "FileVisitor", "SimpleFileVisitor", "PathMatcher",
                "WatchService", "WatchKey", "FileSystemException", "NoSuchFileException",
                "FileAlreadyExistsException", "DirectoryNotEmptyException", "AccessDeniedException",
                "InvalidPathException", "NotDirectoryException", "DirectoryIteratorException");
        jdk("java.net", "URL", "URI", "URISyntaxException", "InetAddress", "Inet4Address", "Inet6Address",
                "InetSocketAddress", "SocketAddress", "Socket", "ServerSocket", "SocketException",
                "SocketTimeoutException", "ConnectException", "BindException", "UnknownHostException",
                "MalformedURLException", "URLEncoder", "URLDecoder", "URLConnection", "HttpURLConnection",
                "Proxy", "NetworkInterface", "InterfaceAddress", "DatagramSocket");
        jdk("java.util.regex", "Pattern", "Matcher", "PatternSyntaxException", "MatchResult");
        jdk("java.text", "SimpleDateFormat", "DateFormat", "DecimalFormat", "NumberFormat", "MessageFormat",
                "Format", "ParseException", "Normalizer");
        jdk("java.lang.reflect", "Method", "Field", "Constructor", "Member", "Type", "ParameterizedType",
                "TypeVariable", "GenericArrayType", "WildcardType", "AnnotatedElement", "Array", "Modifier",
                "Proxy", "InvocationHandler", "InvocationTargetException", "UndeclaredThrowableException");
        jdk("java.security", "MessageDigest", "SecureRandom", "NoSuchAlgorithmException",
                "GeneralSecurityException", "KeyStore", "PrivateKey", "PublicKey", "Signature", "Principal");
        jdk("java.sql", "SQLException", "SQLTimeoutException", "Connection", "Statement", "PreparedStatement",
                "CallableStatement", "ResultSet", "ResultSetMetaData", "DatabaseMetaData", "Timestamp",
                "Time", "DriverManager", "BatchUpdateException");
    }

    private static void jdk(String pkg, String... names) {
        for (String n : names) {
            JDK_COMMON_TYPES.put(n, pkg + "." + n);
        }
    }

    /**
     * JDK8/guava 常用方法签名表（声明处 owner → [name, argc, 完整签名, 返回类型模板]）。
     * 签名格式从生产编译索引实测提取（TypeMirror.toString）；返回类型模板含类型参数占位符
     * （E/T/K/V），代入接收者泛型实参后续链；未映射占位符 → null（宁缺勿错，链断而非错边）。
     * 外部依赖边据此从「无参展示边」升级为「声明处真实签名边」（List#forEach → Iterable#forEach）。
     */
    private static final Map<String, List<String[]>> JDK_METHODS = new HashMap<String, List<String[]>>();
    /** JDK 类继承链（子 → 直接父接口/类）：方法查找沿链上溯到声明处（对齐 javac 挂声明类）。 */
    private static final Map<String, String> JDK_HIERARCHY = new HashMap<String, String>();
    /** JDK 泛型类型的类型参数名（返回类型模板代入映射用）。 */
    private static final Map<String, String[]> JDK_TYPE_PARAMS = new HashMap<String, String[]>();
    /** guava collect 常用类（简名 → FQN）：本文件 wildcard import（com.google.common.collect.*）消歧用——
     *  全仓同名类（assertj/jcommander 的 Lists 等）会淹没唯一 import 仲裁，但本文件 wildcard 是强信号。 */
    private static final Map<String, String> GUAVA_COLLECT_TYPES = new HashMap<String, String>();

    static {
        jdk("com.google.common.collect", "Lists", "Sets", "Maps", "Queues", "Collections2", "Iterables",
                "Iterators", "ImmutableList", "ImmutableMap", "ImmutableSet", "ImmutableSortedMap",
                "ImmutableSortedSet", "ImmutableMultimap", "ArrayListMultimap", "HashMultimap",
                "LinkedListMultimap", "TreeMultimap", "Multimap", "ListMultimap", "SetMultimap",
                "Multiset", "HashMultiset", "TreeMultiset", "BiMap", "HashBiMap", "Table",
                "HashBasedTable", "TreeBasedTable", "Range", "BoundType");
        // ── 集合层级（方法沿链上溯到声明处：List#forEach → Iterable#forEach，List#stream → Collection#stream）──
        jdkParent("java.util.ArrayList,java.util.LinkedList,java.util.Vector,java.util.Stack,java.util.CopyOnWriteArrayList", "java.util.List");
        jdkParent("java.util.HashSet,java.util.LinkedHashSet,java.util.TreeSet,java.util.CopyOnWriteArraySet,java.util.ConcurrentSkipListSet", "java.util.Set");
        jdkParent("java.util.List,java.util.Set,java.util.Queue,java.util.Deque", "java.util.Collection");
        jdkParent("java.util.HashMap,java.util.LinkedHashMap,java.util.TreeMap,java.util.Hashtable,java.util.concurrent.ConcurrentHashMap,java.util.concurrent.ConcurrentSkipListMap", "java.util.Map");
        jdkParent("java.util.Collection", "java.lang.Iterable");
        jdkParams("java.util.List,java.util.ArrayList,java.util.LinkedList,java.util.Set,java.util.HashSet,java.util.LinkedHashSet,java.util.TreeSet,java.util.Collection,java.util.Queue,java.util.Deque,java.util.Vector,java.util.Stack", "E");
        jdkParams("java.util.Optional,java.util.stream.Stream,java.lang.Iterable", "T");
        jdkParams("java.util.Map,java.util.HashMap,java.util.LinkedHashMap,java.util.TreeMap,java.util.concurrent.ConcurrentHashMap", "K", "V");

        // ── java.lang.Iterable ──
        jdkm("java.lang.Iterable", "forEach", 1, "forEach(java.util.function.Consumer<? super T>)", null);
        jdkm("java.lang.Iterable", "iterator", 0, "iterator()", "java.util.Iterator<T>");
        jdkm("java.lang.Iterable", "spliterator", 0, "spliterator()", null);
        // ── java.util.Collection ──
        jdkm("java.util.Collection", "stream", 0, "stream()", "java.util.stream.Stream<E>");
        jdkm("java.util.Collection", "size", 0, "size()", null);
        jdkm("java.util.Collection", "isEmpty", 0, "isEmpty()", null);
        jdkm("java.util.Collection", "contains", 1, "contains(java.lang.Object)", null);
        jdkm("java.util.Collection", "add", 1, "add(E)", null);
        jdkm("java.util.Collection", "addAll", 1, "addAll(java.util.Collection<? extends E>)", null);
        jdkm("java.util.Collection", "remove", 1, "remove(java.lang.Object)", null);
        jdkm("java.util.Collection", "clear", 0, "clear()", null);
        jdkm("java.util.Collection", "iterator", 0, "iterator()", "java.util.Iterator<E>");
        jdkm("java.util.Collection", "toArray", 0, "toArray()", null);
        // ── java.util.List ──
        jdkm("java.util.List", "get", 1, "get(int)", "E");
        jdkm("java.util.List", "add", 1, "add(E)", null);
        jdkm("java.util.List", "contains", 1, "contains(java.lang.Object)", null);
        jdkm("java.util.List", "addAll", 1, "addAll(java.util.Collection<? extends E>)", null);
        jdkm("java.util.List", "toArray", 0, "toArray()", null);
        jdkm("java.util.List", "set", 2, "set(int,E)", "E");
        jdkm("java.util.List", "indexOf", 1, "indexOf(java.lang.Object)", null);
        jdkm("java.util.List", "remove", 1, "remove(int)", "E");
        jdkm("java.util.List", "sort", 1, "sort(java.util.Comparator<? super E>)", null);
        jdkm("java.util.List", "size", 0, "size()", null);
        jdkm("java.util.List", "isEmpty", 0, "isEmpty()", null);
        // ── java.util.Set ──
        jdkm("java.util.Set", "add", 1, "add(E)", null);
        jdkm("java.util.Set", "size", 0, "size()", null);
        jdkm("java.util.Set", "contains", 1, "contains(java.lang.Object)", null);
        jdkm("java.util.Set", "isEmpty", 0, "isEmpty()", null);
        // ── java.util.Map ──
        jdkm("java.util.Map", "get", 1, "get(java.lang.Object)", "V");
        jdkm("java.util.Map", "put", 2, "put(K,V)", "V");
        jdkm("java.util.Map", "getOrDefault", 2, "getOrDefault(java.lang.Object,V)", "V");
        jdkm("java.util.Map", "containsKey", 1, "containsKey(java.lang.Object)", null);
        jdkm("java.util.Map", "containsValue", 1, "containsValue(java.lang.Object)", null);
        jdkm("java.util.Map", "keySet", 0, "keySet()", "java.util.Set<K>");
        jdkm("java.util.Map", "values", 0, "values()", "java.util.Collection<V>");
        jdkm("java.util.Map", "entrySet", 0, "entrySet()", "java.util.Set<java.util.Map.Entry<K,V>>");
        jdkm("java.util.Map", "size", 0, "size()", null);
        jdkm("java.util.Map", "isEmpty", 0, "isEmpty()", null);
        jdkm("java.util.Map", "remove", 1, "remove(java.lang.Object)", "V");
        jdkm("java.util.Map", "forEach", 1, "forEach(java.util.function.BiConsumer<? super K,? super V>)", null);
        jdkm("java.util.Map", "merge", 3, "merge(K,V,java.util.function.BiFunction<? super V,? super V,? extends V>)", "V");
        jdkm("java.util.Map", "computeIfAbsent", 2, "computeIfAbsent(K,java.util.function.Function<? super K,? extends V>)", "V");
        // ── java.util.stream.Stream ──
        jdkm("java.util.stream.Stream", "filter", 1, "filter(java.util.function.Predicate<? super T>)", "java.util.stream.Stream<T>");
        jdkm("java.util.stream.Stream", "map", 1, "map(java.util.function.Function<? super T,? extends R>)", "java.util.stream.Stream<R>");
        jdkm("java.util.stream.Stream", "flatMap", 1, "flatMap(java.util.function.Function<? super T,? extends java.util.stream.Stream<? extends R>>)", "java.util.stream.Stream<R>");
        jdkm("java.util.stream.Stream", "collect", 1, "collect(java.util.stream.Collector<? super T,A,R>)", null);
        jdkm("java.util.stream.Stream", "forEach", 1, "forEach(java.util.function.Consumer<? super T>)", null);
        jdkm("java.util.stream.Stream", "forEachOrdered", 1, "forEachOrdered(java.util.function.Consumer<? super T>)", null);
        jdkm("java.util.stream.Stream", "peek", 1, "peek(java.util.function.Consumer<? super T>)", "java.util.stream.Stream<T>");
        jdkm("java.util.stream.Stream", "distinct", 0, "distinct()", "java.util.stream.Stream<T>");
        jdkm("java.util.stream.Stream", "sorted", 0, "sorted()", "java.util.stream.Stream<T>");
        jdkm("java.util.stream.Stream", "sorted", 1, "sorted(java.util.Comparator<? super T>)", "java.util.stream.Stream<T>");
        jdkm("java.util.stream.Stream", "limit", 1, "limit(long)", "java.util.stream.Stream<T>");
        jdkm("java.util.stream.Stream", "skip", 1, "skip(long)", "java.util.stream.Stream<T>");
        jdkm("java.util.stream.Stream", "count", 0, "count()", null);
        jdkm("java.util.stream.Stream", "findAny", 0, "findAny()", null);
        jdkm("java.util.stream.Stream", "findFirst", 0, "findFirst()", null);
        jdkm("java.util.stream.Stream", "anyMatch", 1, "anyMatch(java.util.function.Predicate<? super T>)", null);
        jdkm("java.util.stream.Stream", "allMatch", 1, "allMatch(java.util.function.Predicate<? super T>)", null);
        jdkm("java.util.stream.Stream", "noneMatch", 1, "noneMatch(java.util.function.Predicate<? super T>)", null);
        jdkm("java.util.stream.Stream", "max", 1, "max(java.util.Comparator<? super T>)", null);
        jdkm("java.util.stream.Stream", "min", 1, "min(java.util.Comparator<? super T>)", null);
        // ── java.util.stream.Collectors（静态工厂；toList/toSet 返回类型在链式推断里特判）──
        jdkm("java.util.stream.Collectors", "toList", 0, "toList()", null);
        jdkm("java.util.stream.Collectors", "toSet", 0, "toSet()", null);
        jdkm("java.util.stream.Collectors", "joining", 1, "joining(java.lang.CharSequence)", null);
        jdkm("java.util.stream.Collectors", "joining", 0, "joining()", null);
        jdkm("java.util.stream.Collectors", "counting", 0, "counting()", null);
        jdkm("java.util.stream.Collectors", "groupingBy", 1, "groupingBy(java.util.function.Function<? super T,? extends K>)", null);
        jdkm("java.util.stream.Collectors", "toMap", 2, "toMap(java.util.function.Function<? super T,? extends K>,java.util.function.Function<? super T,? extends V>)", null);
        jdkm("java.util.stream.Collectors", "toMap", 3, "toMap(java.util.function.Function<? super T,? extends K>,java.util.function.Function<? super T,? extends U>,java.util.function.BinaryOperator<U>)", null);
        jdkm("java.util.stream.Collectors", "groupingBy", 2, "groupingBy(java.util.function.Function<? super T,? extends K>,java.util.stream.Collector<? super T,A,D>)", null);
        jdkm("java.util.stream.Collectors", "joining", 3, "joining(java.lang.CharSequence,java.lang.CharSequence,java.lang.CharSequence)", null);
        jdkm("java.util.stream.Collectors", "mapping", 2, "mapping(java.util.function.Function<? super T,? extends U>,java.util.stream.Collector<? super U,A,R>)", null);
        jdkm("java.util.stream.Stream", "of", 1, "of(T)", "java.util.stream.Stream<T>");
        jdkm("java.util.Optional", "empty", 0, "empty()", "java.util.Optional<T>");
        jdkm("java.util.Set", "remove", 1, "remove(java.lang.Object)", null);
        jdkm("java.util.Set", "iterator", 0, "iterator()", "java.util.Iterator<E>");
        jdkm("java.util.Map", "putIfAbsent", 2, "putIfAbsent(K,V)", "V");
        jdkm("java.util.Map", "getOrDefault", 2, "getOrDefault(java.lang.Object,V)", "V");
        jdkm("java.util.Collections", "unmodifiableMap", 1, "unmodifiableMap(java.util.Map<? extends K,? extends V>)", "java.util.Map<K,V>");
        jdkm("java.util.Collections", "unmodifiableSet", 1, "unmodifiableSet(java.util.Set<? extends T>)", "java.util.Set<T>");
        jdkm("java.util.Collections", "singletonMap", 2, "singletonMap(K,V)", "java.util.Map<K,V>");
        jdkm("com.google.common.collect.Lists", "newLinkedList", 0, "newLinkedList()", null);
        jdkm("com.google.common.collect.Lists", "transform", 2, "transform(java.util.List<F>,java.util.function.Function<? super F,? extends T>)", "java.util.List<T>");
        jdkm("com.google.common.collect.Sets", "newLinkedHashSet", 0, "newLinkedHashSet()", null);
        jdkm("com.google.common.collect.Sets", "difference", 2, "difference(java.util.Set<E>,java.util.Set<E>)", "com.google.common.collect.Sets$SetView<E>");
        jdkm("com.google.common.collect.Maps", "newLinkedHashMap", 0, "newLinkedHashMap()", null);
        jdkm("com.google.common.collect.Maps", "newConcurrentMap", 0, "newConcurrentMap()", null);
        jdkm("com.google.common.collect.Maps", "transformValues", 2, "transformValues(java.util.Map<K,V>,java.util.function.Function<? super V,? extends V2>)", "java.util.Map<K,V2>");
        jdkm("com.google.common.collect.Iterables", "isEmpty", 1, "isEmpty(java.lang.Iterable<?>)", "boolean");
        jdkm("com.google.common.collect.Iterables", "getFirst", 2, "getFirst(java.lang.Iterable<T>,T)", "T");
        jdkm("com.google.common.collect.Collections2", "transform", 2, "transform(java.util.Collection<F>,java.util.function.Function<? super F,? extends T>)", "java.util.Collection<T>");
        // ── java.util.Optional ──
        jdkm("java.util.Optional", "get", 0, "get()", "T");
        jdkm("java.util.Optional", "isPresent", 0, "isPresent()", null);
        jdkm("java.util.Optional", "orElse", 1, "orElse(T)", "T");
        jdkm("java.util.Optional", "orElseGet", 1, "orElseGet(java.util.function.Supplier<? extends T>)", "T");
        jdkm("java.util.Optional", "orElseThrow", 0, "orElseThrow()", "T");
        jdkm("java.util.Optional", "filter", 1, "filter(java.util.function.Predicate<? super T>)", "java.util.Optional<T>");
        jdkm("java.util.Optional", "map", 1, "map(java.util.function.Function<? super T,? extends U>)", "java.util.Optional<U>");
        jdkm("java.util.Optional", "flatMap", 1, "flatMap(java.util.function.Function<? super T,? extends java.util.Optional<? extends U>>)", "java.util.Optional<U>");
        jdkm("java.util.Optional", "ifPresent", 1, "ifPresent(java.util.function.Consumer<? super T>)", null);
        jdkm("java.util.Optional", "ofNullable", 1, "ofNullable(T)", "java.util.Optional<T>");
        jdkm("java.util.Optional", "of", 1, "of(T)", "java.util.Optional<T>");
        // ── java.util.Objects ──
        jdkm("java.util.Objects", "equals", 2, "equals(java.lang.Object,java.lang.Object)", null);
        jdkm("java.util.Objects", "isNull", 1, "isNull(java.lang.Object)", null);
        jdkm("java.util.Objects", "nonNull", 1, "nonNull(java.lang.Object)", null);
        jdkm("java.util.Objects", "requireNonNull", 1, "requireNonNull(T)", "T");
        jdkm("java.util.Objects", "requireNonNull", 2, "requireNonNull(T,java.lang.String)", "T");
        jdkm("java.util.Objects", "hashCode", 1, "hashCode(java.lang.Object)", null);
        jdkm("java.util.Objects", "toString", 1, "toString(java.lang.Object)", "java.lang.String");
        // ── java.lang.Object ──
        jdkm("java.lang.Object", "equals", 1, "equals(java.lang.Object)", null);
        jdkm("java.lang.Object", "hashCode", 0, "hashCode()", null);
        jdkm("java.lang.Object", "toString", 0, "toString()", "java.lang.String");
        jdkm("java.lang.Object", "getClass", 0, "getClass()", null);
        jdkm("java.lang.Object", "<init>", 0, "<init>()", null);
        // ── java.lang.String ──
        jdkm("java.lang.String", "equals", 1, "equals(java.lang.Object)", null);
        jdkm("java.lang.String", "isEmpty", 0, "isEmpty()", null);
        jdkm("java.lang.String", "length", 0, "length()", null);
        jdkm("java.lang.String", "trim", 0, "trim()", "java.lang.String");
        jdkm("java.lang.String", "substring", 1, "substring(int)", "java.lang.String");
        jdkm("java.lang.String", "substring", 2, "substring(int,int)", "java.lang.String");
        jdkm("java.lang.String", "replace", 2, "replace(java.lang.CharSequence,java.lang.CharSequence)", "java.lang.String");
        jdkm("java.lang.String", "charAt", 1, "charAt(int)", null);
        jdkm("java.lang.String", "contains", 1, "contains(java.lang.CharSequence)", null);
        jdkm("java.lang.String", "startsWith", 1, "startsWith(java.lang.String)", null);
        jdkm("java.lang.String", "endsWith", 1, "endsWith(java.lang.String)", null);
        jdkm("java.lang.String", "toLowerCase", 0, "toLowerCase()", "java.lang.String");
        jdkm("java.lang.String", "toUpperCase", 0, "toUpperCase()", "java.lang.String");
        jdkm("java.lang.String", "split", 1, "split(java.lang.String)", "java.lang.String[]");
        jdkm("java.lang.String", "intern", 0, "intern()", "java.lang.String");
        jdkm("java.lang.String", "valueOf", 1, "valueOf(java.lang.Object)", "java.lang.String");
        jdkm("java.lang.String", "valueOf", 1, "valueOf(long)", "java.lang.String");
        jdkm("java.lang.String", "valueOf", 1, "valueOf(int)", "java.lang.String");
        jdkm("java.lang.String", "valueOf", 1, "valueOf(double)", "java.lang.String");
        // ── 包装类 ──
        jdkm("java.lang.Integer", "equals", 1, "equals(java.lang.Object)", null);
        jdkm("java.lang.Integer", "intValue", 0, "intValue()", null);
        jdkm("java.lang.Integer", "toString", 0, "toString()", "java.lang.String");
        jdkm("java.lang.Integer", "valueOf", 1, "valueOf(java.lang.String)", "java.lang.Integer");
        jdkm("java.lang.Integer", "parseInt", 1, "parseInt(java.lang.String)", null);
        jdkm("java.lang.Long", "equals", 1, "equals(java.lang.Object)", null);
        jdkm("java.lang.Long", "longValue", 0, "longValue()", null);
        jdkm("java.lang.Long", "valueOf", 1, "valueOf(java.lang.String)", "java.lang.Long");
        jdkm("java.lang.Long", "parseLong", 1, "parseLong(java.lang.String)", null);
        jdkm("java.lang.Boolean", "equals", 1, "equals(java.lang.Object)", null);
        jdkm("java.lang.Boolean", "toString", 0, "toString()", "java.lang.String");
        jdkm("java.lang.Boolean", "parseBoolean", 1, "parseBoolean(java.lang.String)", null);
        // ── java.time ──
        jdkm("java.time.LocalDateTime", "now", 0, "now()", "java.time.LocalDateTime");
        jdkm("java.time.LocalDateTime", "plusDays", 1, "plusDays(long)", "java.time.LocalDateTime");
        jdkm("java.time.LocalDateTime", "plusHours", 1, "plusHours(long)", "java.time.LocalDateTime");
        jdkm("java.time.LocalDateTime", "plusMinutes", 1, "plusMinutes(long)", "java.time.LocalDateTime");
        jdkm("java.time.LocalDateTime", "minusDays", 1, "minusDays(long)", "java.time.LocalDateTime");
        jdkm("java.time.LocalDateTime", "format", 1, "format(java.time.format.DateTimeFormatter)", "java.lang.String");
        jdkm("java.time.LocalDateTime", "toString", 0, "toString()", "java.lang.String");
        // ── 工具类 ──
        jdkm("java.util.Collections", "singletonList", 1, "singletonList(T)", "java.util.List<T>");
        jdkm("java.util.Collections", "emptyList", 0, "emptyList()", "java.util.List");
        jdkm("java.util.Collections", "emptyMap", 0, "emptyMap()", "java.util.Map");
        jdkm("java.util.Collections", "unmodifiableList", 1, "unmodifiableList(java.util.List<? extends T>)", "java.util.List<T>");
        jdkm("java.util.Collections", "singleton", 1, "singleton(T)", "java.util.Set<T>");
        jdkm("java.util.Arrays", "asList", 1, "asList(T[])", "java.util.List<T>");
        jdkm("java.util.Arrays", "toString", 1, "toString(java.lang.Object[])", "java.lang.String");
        jdkm("java.util.Arrays", "stream", 1, "stream(T[])", "java.util.stream.Stream<T>");
        // ── 集合构造器（隐式/显式 new 边）──
        jdkm("java.util.ArrayList", "<init>", 0, "<init>()", null);
        jdkm("java.util.ArrayList", "<init>", 1, "<init>(java.util.Collection<? extends T>)", null);
        jdkm("java.util.HashMap", "<init>", 0, "<init>()", null);
        jdkm("java.util.HashMap", "<init>", 1, "<init>(int)", null);
        jdkm("java.util.HashSet", "<init>", 0, "<init>()", null);
        jdkm("java.util.HashSet", "<init>", 1, "<init>(java.util.Collection<? extends T>)", null);
        jdkm("java.util.LinkedList", "<init>", 0, "<init>()", null);
        // ── guava collect（静态工厂；签名从生产编译索引实测）──
        jdkm("com.google.common.collect.Lists", "newArrayList", 0, "newArrayList()", null);
        jdkm("com.google.common.collect.Lists", "newArrayList", 1, "newArrayList(E[])", null);
        jdkm("com.google.common.collect.Lists", "newArrayList", 1, "newArrayList(java.lang.Iterable<? extends E>)", null);
        jdkm("com.google.common.collect.Sets", "newHashSet", 0, "newHashSet()", null);
        jdkm("com.google.common.collect.Sets", "newHashSet", 1, "newHashSet(E[])", null);
        jdkm("com.google.common.collect.Maps", "newHashMap", 0, "newHashMap()", null);
    }

    private static void jdkm(String owner, String name, int argc, String sig, String ret) {
        List<String[]> l = JDK_METHODS.get(owner);
        if (l == null) {
            l = new ArrayList<String[]>();
            JDK_METHODS.put(owner, l);
        }
        l.add(new String[]{name, String.valueOf(argc), sig, ret});
    }

    private static void jdkParent(String childrenCsv, String parent) {
        for (String c : childrenCsv.split(",")) {
            JDK_HIERARCHY.put(c, parent);
        }
    }

    private static void jdkParams(String ownersCsv, String... paramNames) {
        for (String o : ownersCsv.split(",")) {
            JDK_TYPE_PARAMS.put(o, paramNames);
        }
    }

    /** 索引产出统计。 */
    public static class Result {
        public long lines;
        public int files;
        public int parseFailures;
        public int defs;
        public int calls;
        public int droppedAmbiguousCalls;
        public long elapsedMs;

        public boolean ok() {
            return lines > 0;
        }

        public String summary() {
            return "files=" + files + " parseFailures=" + parseFailures
                    + " defs=" + defs + " calls=" + calls
                    + " droppedAmbiguous=" + droppedAmbiguousCalls + " lines=" + lines;
        }
    }

    /** 方法签名（sym + 形参个数 + 返回类型，重载仲裁与链式调用推断用）。 */
    private static class MethodSig {
        final String sym;
        final int params;
        final String ret;      // 返回类型（带泛型实参渲染；void/构造器/未知为 null）

        MethodSig(String sym, int params) {
            this(sym, params, null);
        }

        MethodSig(String sym, int params, String ret) {
            this.sym = sym;
            this.params = params;
            this.ret = ret;
        }
    }

    /** 类信息（全局注册表：跨文件调用仲裁与类型解析）。 */
    private class ClassInfo {
        final String fqn;
        final FileCtx ctx;
        final boolean isInterface;
        String superRaw;        // extends 原始名（A1 记录，全表就绪后惰性解析）
        String superClassFqn;
        final Map<String, List<MethodSig>> methods = new HashMap<String, List<MethodSig>>();

        ClassInfo(String fqn, FileCtx ctx, boolean isInterface) {
            this.fqn = fqn;
            this.ctx = ctx;
            this.isInterface = isInterface;
        }

        /** A1：登记声明（sym 占位 null，A2 按声明序回填）。 */
        void addDecl(String name, int params) {
            List<MethodSig> l = methods.get(name);
            if (l == null) {
                l = new ArrayList<MethodSig>();
                methods.put(name, l);
            }
            l.add(new MethodSig(null, params));
        }

        /** A2：按声明序回填 sym 与返回类型（两遍遍历顺序一致，对位安全）。 */
        void fillSym(String name, String sym, String ret) {
            List<MethodSig> l = methods.get(name);
            if (l == null) {
                return;
            }
            for (MethodSig m : l) {
                if (m.sym == null) {
                    // 占位不可变，重建
                    l.set(l.indexOf(m), new MethodSig(sym, m.params, ret));
                    return;
                }
            }
        }

        /** 惰性解析父类 FQN（全表就绪后）。 */
        String superFqn() {
            if (superRaw != null && superClassFqn == null) {
                superClassFqn = resolveDottedPrefix(superRaw, ctx);
            }
            return superClassFqn;
        }

        /** A2：Lombok/MapStruct 合成方法登记（A1 无占位，直接带完整 sym 与返回类型）。 */
        void addSynthetic(String name, String sym, int params, String ret) {
            List<MethodSig> l = methods.get(name);
            if (l == null) {
                l = new ArrayList<MethodSig>();
                methods.put(name, l);
            }
            l.add(new MethodSig(sym, params, ret));
        }
    }

    /** 待仲裁调用记录（def 全部注册后统一解析写出）。 */
    private static class PendingCall {
        final String from;
        final String calleeName;      // 方法名；构造器为 <init>
        final int argc;
        final String[] argTypes;      // 实参静态类型（外部依赖签名渲染用；不可知元素为 null）
        String ownerHint;             // 接收者类型 FQN（仓内或外部 import），null = 未知走全局唯一仲裁
        final boolean isCtor;
        final String file;
        final int line;
        // 链式接收者解析失败时的重试上下文（Phase B 前用全量外部字段类型表重算 owner）
        MethodCallExpr retryMce;
        FileCtx retryCtx;
        ClassInfo retryClass;
        VarScope retryScope;

        PendingCall(String from, String calleeName, int argc, String ownerHint, String[] argTypes,
                    boolean isCtor, String file, int line) {
            this.from = from;
            this.calleeName = calleeName;
            this.argc = argc;
            this.ownerHint = ownerHint;
            this.argTypes = argTypes;
            this.isCtor = isCtor;
            this.file = file;
            this.line = line;
        }
    }

    /** 单文件解析上下文（import 表与同文件类型表）。 */
    private static class FileCtx {
        final String pkg;
        final Map<String, String> singleImports = new HashMap<String, String>();   // 简名 → FQN
        final List<String> wildcardImports = new ArrayList<String>();              // 通配包名
        final Map<String, String> staticMethods = new HashMap<String, String>();   // 静态导入方法 → owner FQN
        final List<String> staticWildcardOwners = new ArrayList<String>();
        final Map<String, String> fileTypes = new HashMap<String, String>();       // 本文件类简名 → FQN

        FileCtx(String pkg) {
            this.pkg = pkg;
        }
    }

    /** 方法级词法作用域（接收者类型推断：参数 + 局部变量 + 字段）。值存完整类型（带泛型实参，
     *  typeForSym 渲染格式），lambda 参数元素提取与链式泛型代入用；owner 仲裁处 stripGenerics 剥离。 */
    private static class VarScope {
        final Map<String, String> vars = new HashMap<String, String>();

        void put(String name, String rawTypeFqn) {
            if (name != null && rawTypeFqn != null) {
                vars.put(name, rawTypeFqn);
            }
        }
    }

    private final Map<String, ClassInfo> classesByFqn = new HashMap<String, ClassInfo>();
    private final Map<String, List<ClassInfo>> classesBySimpleName = new HashMap<String, List<ClassInfo>>();
    /** 全仓 import 收集（简名 → FQN 集合）：外部 wildcard import 的类靠其他文件的显式 import 唯一仲裁。 */
    private final Map<String, Set<String>> globalImports = new HashMap<String, Set<String>>();
    /** 全局方法名索引（不含 <init>，无接收者线索时的唯一同名仲裁）。 */
    private final Map<String, List<MethodSig>> globalMethodsByName = new HashMap<String, List<MethodSig>>();
    /** 外部类字段类型表（FQN → 字段 → 完整类型）：同仓 setter 调用的实参类型聚合（多方信息聚合，
     *  免 jar 推导外部 getter 返回类型——dto.setX(String) 的信息让 dto.getX().trim() 续链）。 */
    private final Map<String, Map<String, String>> externalFieldTypes = new HashMap<String, Map<String, String>>();
    /** 仓内类字段类型表（FQN → 字段 → 完整类型）：MapStruct xToY 辅助方法合成用
     *  （源/目标类型同名不同型字段对 → helper 签名）。 */
    private final Map<String, Map<String, String>> classFieldTypes = new HashMap<String, Map<String, String>>();
    /** @Mapper 接口的抽象方法（Impl FQN → [参数串, 返回类型]）：post-pass helper 合成输入。 */
    private final Map<String, List<String[]>> mapperAbstractMethods = new HashMap<String, List<String[]>>();
    /** 合成 Impl FQN → 接口源文件 relPath（helper def 输出用）。 */
    private final Map<String, String> mapperImplFiles = new HashMap<String, String>();
    /** 外部接口 FQN 集合（多方信息聚合：全仓 implements/接口 extends 解析结果——匿名类 super 边
     *  挂 Object 还是类 ctor 的判定依据，免 jar）。 */
    private final Set<String> externalInterfaces = new HashSet<String>();
    /** 匿名类 ctor 等散装 def（collectCallsIn 时机产出，写盘前并入）。 */
    private final Set<String> anonDefs = new java.util.LinkedHashSet<String>();
    private final List<PendingCall> pendingCalls = new ArrayList<PendingCall>();
    private final List<String> callLines = new ArrayList<String>();

    /**
     * 对仓库目录做静态索引，产出 jsonl。
     *
     * @param repoDir  仓库工作副本目录
     * @param outJsonl 输出文件（已存在则覆盖）
     */
    public Result index(File repoDir, File outJsonl) throws Exception {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        List<File> javaFiles = collectJavaFiles(repoDir);
        if (javaFiles.isEmpty()) {
            return r;
        }

        ParserConfiguration pc = new ParserConfiguration();
        pc.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        pc.setCharacterEncoding(StandardCharsets.UTF_8);
        JavaParser parser = new JavaParser(pc);

        // Phase A1（注册遍历）：全量解析，仅登记类/方法声明与 extends 原始名——
        // 两遍设计根除“文件处理顺序依赖”（wildcard import / 同包 / 继承链均需全表就绪后解析）
        for (File f : javaFiles) {
            ParseResult<CompilationUnit> pr = parser.parse(f);
            if (!pr.isSuccessful() || !pr.getResult().isPresent()) {
                r.parseFailures++;
                continue;
            }
            r.files++;
            scanCompilationUnit(pr.getResult().get(), null, null, true);
        }

        // Phase A2（产出遍历）：全表就绪后重解析，渲染 def（含参数 FQN/注解）+ 调用记录缓存
        List<String> defLines = new ArrayList<String>();
        for (File f : javaFiles) {
            ParseResult<CompilationUnit> pr = parser.parse(f);
            if (!pr.isSuccessful() || !pr.getResult().isPresent()) {
                continue;
            }
            scanCompilationUnit(pr.getResult().get(), relativePath(repoDir, f), defLines, false);
        }

        // 多方信息聚合：外部 setter 实参类型 → 字段类型表（同仓调用点互供信息：
        // dto.setX(Type) 出现过，dto.getX() 的返回类型即可推导——免 jar、免兄弟仓源码）
        for (PendingCall c : pendingCalls) {
            if (c.isCtor || c.ownerHint == null || classesByFqn.containsKey(c.ownerHint)
                    || !c.calleeName.startsWith("set") || c.calleeName.length() <= 3
                    || c.argc != 1 || c.argTypes == null || c.argTypes.length != 1 || c.argTypes[0] == null) {
                continue;
            }
            String field = Character.toLowerCase(c.calleeName.charAt(3)) + c.calleeName.substring(4);
            Map<String, String> fields = externalFieldTypes.get(c.ownerHint);
            if (fields == null) {
                fields = new HashMap<String, String>();
                externalFieldTypes.put(c.ownerHint, fields);
            }
            if (!fields.containsKey(field)) {
                fields.put(field, c.argTypes[0]);
            }
        }
        // 链式接收者解析失败重试（A2 时字段类型表未齐——跨文件 setter 在 getter 之后才出现）
        for (PendingCall c : pendingCalls) {
            if (c.retryMce != null) {
                c.ownerHint = ownerOfCall(c.retryMce, c.retryCtx, c.retryClass, c.retryScope);
            }
        }
        // MapStruct xToY 辅助方法合成（源/目标字段表全量就绪后：同名不同型字段对 → helper def）
        synthesizeMapStructHelpers(defLines);
        defLines.addAll(anonDefs);

        // Phase B：def 齐备后仲裁调用边（仓内签名 join / 外部展示边 / 歧义丢弃）
        for (PendingCall c : pendingCalls) {
            String to = resolveCallTarget(c);
            if (to == null) {
                r.droppedAmbiguousCalls++;
                continue;
            }
            callLines.add("{\"t\":\"call\",\"from\":\"" + esc(c.from)
                    + "\",\"to\":\"" + esc(to)
                    + "\",\"file\":\"" + esc(c.file) + "\",\"line\":" + c.line + "}");
        }

        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outJsonl), StandardCharsets.UTF_8));
            for (String line : defLines) {
                w.write(line);
                w.write('\n');
            }
            for (String line : callLines) {
                w.write(line);
                w.write('\n');
            }
        } finally {
            if (w != null) {
                w.close();
            }
        }
        r.defs = defLines.size();
        r.calls = callLines.size();
        r.lines = defLines.size() + callLines.size();
        r.elapsedMs = System.currentTimeMillis() - t0;
        return r;
    }

    // ─────────────────────────── Phase A：扫描 ───────────────────────────

    /**
     * @param registerOnly A1 注册遍历（仅登记类/方法声明与 extends 原始名，不产出）；
 *                     A2 产出遍历（def 渲染 + 调用收集，全表就绪）
     */
    private void scanCompilationUnit(CompilationUnit cu, String relPath, List<String> defLines,
                                     boolean registerOnly) {
        String pkg = cu.getPackageDeclaration().isPresent()
                ? cu.getPackageDeclaration().get().getNameAsString() : "";
        FileCtx ctx = new FileCtx(pkg);
        for (com.github.javaparser.ast.ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isStatic()) {
                if (imp.isAsterisk()) {
                    ctx.staticWildcardOwners.add(stripAsterisk(name));
                } else {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        ctx.staticMethods.put(name.substring(dot + 1), name.substring(0, dot));
                    }
                }
                continue;
            }
            if (imp.isAsterisk()) {
                ctx.wildcardImports.add(stripAsterisk(name));
            } else {
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    String simple = name.substring(dot + 1);
                    ctx.singleImports.put(simple, name);
                    Set<String> gi = globalImports.get(simple);
                    if (gi == null) {
                        gi = new HashSet<String>();
                        globalImports.put(simple, gi);
                    }
                    gi.add(name);
                }
            }
        }
        if (!registerOnly) {
            // A2 预热：先登记本文件全部类型（含嵌套），使同文件类型引用与声明顺序无关
            for (TypeDeclaration<?> td : cu.getTypes()) {
                prewarmType(td, pkg, ctx);
            }
        }
        for (TypeDeclaration<?> td : cu.getTypes()) {
            visitType(td, pkg, ctx, relPath, defLines, registerOnly);
        }
    }

    /** A2 预热：登记本文件全部类型（含嵌套）到 fileTypes。 */
    private void prewarmType(TypeDeclaration<?> td, String prefix, FileCtx ctx) {
        String fqn = prefix.isEmpty() ? td.getNameAsString() : prefix + "." + td.getNameAsString();
        ctx.fileTypes.put(td.getNameAsString(), fqn);
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof TypeDeclaration) {
                prewarmType((TypeDeclaration<?>) member, fqn, ctx);
            }
        }
    }

    /** 递归访问类型声明：A1 登记 / A2 产出 def 与调用提取。 */
    private void visitType(TypeDeclaration<?> td, String prefix, FileCtx ctx,
                           String relPath, List<String> defLines, boolean registerOnly) {
        String simpleName = td.getNameAsString();
        String fqn = prefix.isEmpty() ? simpleName : prefix + "." + simpleName;
        ctx.fileTypes.put(simpleName, fqn);
        boolean isInterface = td instanceof ClassOrInterfaceDeclaration
                && ((ClassOrInterfaceDeclaration) td).isInterface();

        ClassInfo ci = classesByFqn.get(fqn);
        if (ci == null) {
            ci = new ClassInfo(fqn, ctx, isInterface);
            classesByFqn.put(fqn, ci);
        }
        registerSimpleName(simpleName, ci);
        // extends（仅类；接口多继承对方法查找价值低，跳过）。原始名惰性解析（全表就绪后）
        if (td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cd = (ClassOrInterfaceDeclaration) td;
            if (cd.getExtendedTypes().isNonEmpty()) {
                ci.superRaw = dottedOfType(cd.getExtendedTypes().get(0));
            }
            // 外部接口聚合（多方信息）：implements 目标必是接口；接口 extends 的也是接口。
            // 解析失败（外部）则入 externalInterfaces，供匿名类 super 边判定（挂 Object 而非类 ctor）。
            for (ClassOrInterfaceType it : cd.getImplementedTypes()) {
                collectExternalInterface(dottedOfType(it), ctx);
            }
            if (isInterface) {
                for (ClassOrInterfaceType it : cd.getExtendedTypes()) {
                    collectExternalInterface(dottedOfType(it), ctx);
                }
            }
        }
        if (registerOnly) {
            for (BodyDeclaration<?> member : td.getMembers()) {
                if (member instanceof CallableDeclaration) {
                    CallableDeclaration<?> cd = (CallableDeclaration<?>) member;
                    ci.addDecl(cd instanceof ConstructorDeclaration ? "<init>" : cd.getNameAsString(),
                            cd.getParameters().size());
                } else if (member instanceof TypeDeclaration) {
                    visitType((TypeDeclaration<?>) member, fqn, ctx, null, null, true);
                }
            }
            return;
        }

        defLines.add("{\"t\":\"def\",\"kind\":\"class\",\"sym\":\"" + esc(fqn)
                + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + lineOf(td.getName()) + "}");

        // MapStruct @Mapper 接口：镜像合成 XxxImpl（编译期生成源码被索引，仓内无源码）
        if (td instanceof ClassOrInterfaceDeclaration) {
            synthesizeMapStructImpl((ClassOrInterfaceDeclaration) td, fqn, ctx, relPath, defLines);
            synthesizeLombokCtors(td, ci, ctx, relPath, defLines);
            synthesizeBuilder((ClassOrInterfaceDeclaration) td, ci, ctx, relPath, defLines);
        }

        // 成员分派：字段/初始化块（from=?）→ 方法/构造器（from=方法 sym）→ 嵌套类递归
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof FieldDeclaration) {
                FieldDeclaration fd = (FieldDeclaration) member;
                synthesizeLombokAccessors(fd, td, ci, ctx, relPath, defLines);
                collectFieldTypes(ci.fqn, fd, ctx);
                collectCallsIn(fd, ctx, ci, relPath, "?", null);
            } else if (member instanceof InitializerDeclaration) {
                collectCallsIn(member, ctx, ci, relPath, "?", null);
            } else if (member instanceof EnumConstantDeclaration) {
                collectCallsIn(member, ctx, ci, relPath, "?", null); // 枚举常量（含匿名类体）
            } else if (member instanceof CallableDeclaration) {
                CallableDeclaration<?> cd = (CallableDeclaration<?>) member;
                boolean isCtor = cd instanceof ConstructorDeclaration;
                String name = isCtor ? "<init>" : cd.getNameAsString();
                StringBuilder params = new StringBuilder();
                for (Parameter p : cd.getParameters()) {
                    if (params.length() > 0) {
                        params.append(',');
                    }
                    // 对齐 javac8 AnnotatedType.toString：(@A @B :: T)（仅 TYPE_USE 语义注解，排除参数绑定注解）
                    String ann = annotationsOf(p, ctx);
                    if (!ann.isEmpty()) {
                        params.append(ann).append(" :: ");
                    }
                    params.append(typeForSym(p.getType(), ctx));
                    if (p.isVarArgs()) {
                        params.append("[]"); // 编译期 varargs 形参类型是数组
                    }
                    if (!ann.isEmpty()) {
                        params.append(')');
                    }
                }
                String methodSym = fqn + "#" + name + "(" + params + ")";
                ci.fillSym(name, methodSym, retOf(cd, ctx));
                if (!"<init>".equals(name)) {
                    registerGlobalMethod(name, methodSym, cd.getParameters().size());
                }
                defLines.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(methodSym)
                        + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + lineOf(cd.getName()) + "}");

                // 方法体调用提取：from=方法 sym；作用域 = 参数 + 字段 + 方法内局部变量（完整类型带泛型，
                // lambda 参数元素提取与链式泛型代入用）；lambda 实参预绑定后收集调用
                VarScope scope = new VarScope();
                for (Parameter p : cd.getParameters()) {
                    putVar(scope, p.getNameAsString(), p.getType(), ctx);
                }
                collectFields(td, ctx, scope);
                if (cd instanceof MethodDeclaration) {
                    MethodDeclaration md = (MethodDeclaration) cd;
                    if (md.getBody().isPresent()) {
                        for (VariableDeclarator vd : md.findAll(VariableDeclarator.class)) {
                            putVar(scope, vd.getNameAsString(), vd.getType(), ctx);
                        }
                    }
                }
                bindLambdaParams(cd, ctx, ci, scope);
                collectCallsIn(cd, ctx, ci, relPath, methodSym, scope);
                // 隐式 super() 边（编译器插入，生产索引有边——Object#<init>() 等；
                // 显式 this()/super() 时无）：无 extends 挂 Object，extends 解析失败跳过（宁缺勿错）
                if (isCtor && cd.findAll(ExplicitConstructorInvocationStmt.class).isEmpty()) {
                    String superFqn = ci.superRaw == null ? "java.lang.Object" : ci.superFqn();
                    if (superFqn != null) {
                        pendingCalls.add(new PendingCall(methodSym, "<init>", 0, superFqn,
                                null, true, relPath, lineOf(cd.getName())));
                    }
                }
            } else if (member instanceof TypeDeclaration) {
                visitType((TypeDeclaration<?>) member, fqn, ctx, relPath, defLines, false);
            }
        }
    }

    /** 参数 TYPE_USE 语义注解渲染（空格连接，如 "(@javax.validation.Valid"）；无有效注解返回空。 */
    private String annotationsOf(Parameter p, FileCtx ctx) {
        if (p.getAnnotations().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("(");
        int n = 0;
        for (com.github.javaparser.ast.expr.AnnotationExpr a : p.getAnnotations()) {
            if (!(a instanceof MarkerAnnotationExpr)) {
                continue; // javac8 AnnotatedType.toString 只渲染无值注解（带值注解直接丢弃，如 @BizIdentityItem("x")）
            }
            String simple = a.getNameAsString();
            int lastDot = simple.lastIndexOf('.');
            String bare = lastDot < 0 ? simple : simple.substring(lastDot + 1);
            if (BINDING_ANNOTATIONS.contains(bare)) {
                continue; // 参数绑定/序列化注解无 TYPE_USE，javac 不渲染
            }
            String fqn = simple.indexOf('.') > 0
                    ? resolveDottedPrefix(simple, ctx) : resolveSimpleName(simple, ctx);
            if (fqn == null) {
                continue;
            }
            if (n++ > 0) {
                sb.append(' ');
            }
            sb.append('@').append(fqn);
        }
        return n == 0 ? "" : sb.toString();
    }

    /** ClassOrInterfaceType → 点分原始名（不带泛型，未解析）。 */
    private String dottedOfType(ClassOrInterfaceType t) {
        StringBuilder sb = new StringBuilder();
        ClassOrInterfaceType cur = t;
        while (cur.getScope().isPresent()) {
            sb.insert(0, "." + cur.getNameAsString()); // 点在段前（与 resolveTypeName 同步，多段链不再拼错）
            cur = cur.getScope().get();
        }
        sb.insert(0, cur.getNameAsString());
        return sb.toString();
    }

    /** 方法返回类型（typeForSym 渲染，带声明处泛型实参；void/构造器无链式意义为 null）。 */
    private String retOf(CallableDeclaration<?> cd, FileCtx ctx) {
        if (!(cd instanceof MethodDeclaration)) {
            return null;
        }
        String s = typeForSym(((MethodDeclaration) cd).getType(), ctx);
        return "void".equals(s) ? null : s;
    }

    /** 变量入作用域：完整类型（带泛型实参）；基本类型无接收者调用意义不入表。 */
    private void putVar(VarScope scope, String name, Type type, FileCtx ctx) {
        if (type instanceof PrimitiveType) {
            return;
        }
        scope.put(name, typeForSym(type, ctx));
    }

    /** 收集类型自身声明的字段（供接收者类型推断；继承字段不做——解析失败走全局唯一仲裁）。 */
    private void collectFields(TypeDeclaration<?> td, FileCtx ctx, VarScope scope) {
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof FieldDeclaration) {
                for (VariableDeclarator vd : ((FieldDeclaration) member).getVariables()) {
                    putVar(scope, vd.getNameAsString(), vd.getType(), ctx);
                }
            }
        }
        // Lombok 日志注解合成 log 字段（显式声明优先）：接收者推断为外部 Logger，
        // log.info(...) 输出外部展示边 org.slf4j.Logger#info（编译期从 jar 解析签名，静态无参属预期差异）
        if (!scope.vars.containsKey("log")) {
            if (hasLombokAnnotation(td, "Slf4j")) {
                scope.put("log", "org.slf4j.Logger");
            } else if (hasLombokAnnotation(td, "Log4j2")) {
                scope.put("log", "org.apache.logging.log4j.Logger");
            } else if (hasLombokAnnotation(td, "CommonsLog")) {
                scope.put("log", "org.apache.commons.logging.Log");
            }
        }
    }

    /** 在节点子树内提取调用记录（方法调用 / 构造 / this()&amp;super()）。 */
    private void collectCallsIn(com.github.javaparser.ast.Node node, FileCtx ctx, ClassInfo currentClass,
                                String relPath, String fromSym, VarScope scope) {
        for (MethodCallExpr mce : node.findAll(MethodCallExpr.class)) {
            String owner = ownerOfCall(mce, ctx, currentClass, scope);
            PendingCall pc = new PendingCall(fromSym, mce.getNameAsString(), mce.getArguments().size(),
                    owner, argTypesOf(mce, ctx, currentClass, scope), false, relPath, lineOf(mce.getName()));
            if (owner == null && mce.getScope().isPresent()) {
                pc.retryMce = mce;
                pc.retryCtx = ctx;
                pc.retryClass = currentClass;
                pc.retryScope = scope;
            }
            pendingCalls.add(pc);
        }
        for (ObjectCreationExpr oce : node.findAll(ObjectCreationExpr.class)) {
            String typeFqn = resolveTypeName(oce.getType(), ctx);
            // 匿名类（new Iface(){...}）：编译期匿名类自身有 ctor def 与调用边（生产实测形态
            //  EnclosingCls.methodName.#<init>()），ctor 内隐式 super() 边——super 是接口时挂
            //  Object（匿名实现接口 extends Object）。无实参限定（有实参的 ctor 形参透传形态无实证，保守）。
            if (oce.getAnonymousClassBody().isPresent() && typeFqn != null
                    && oce.getArguments().isEmpty()
                    && fromSym != null && fromSym.indexOf('#') > 0) {
                String mn = fromSym.substring(fromSym.indexOf('#') + 1);
                mn = mn.substring(0, Math.max(0, mn.indexOf('(')));
                String anonOwner = currentClass.fqn + "." + mn + ".";
                String anonSym = anonOwner + "#<init>()";
                int line = lineOf(oce.getType().getName());
                pendingCalls.add(new PendingCall(fromSym, "<init>", 0, anonOwner, null, true, relPath, line));
                anonDefs.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(anonSym)
                        + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + line + "}");
                if (isProbablyInterface(typeFqn)) {
                    pendingCalls.add(new PendingCall(anonSym, "<init>", 0,
                            "java.lang.Object", null, true, relPath, line));
                } else if (classesByFqn.containsKey(typeFqn)) {
                    pendingCalls.add(new PendingCall(anonSym, "<init>", 0,
                            typeFqn, null, true, relPath, line));
                } // 外部类非接口：无 jar 无法确证 ctor 形态，宁缺勿错
                continue;
            }
            pendingCalls.add(new PendingCall(fromSym, "<init>", oce.getArguments().size(),
                    typeFqn, argTypesOf(oce.getArguments(), ctx, currentClass, scope), true, relPath,
                    lineOf(oce.getType().getName())));
        }
        for (ExplicitConstructorInvocationStmt eci : node.findAll(ExplicitConstructorInvocationStmt.class)) {
            String owner = eci.isThis() ? currentClass.fqn : currentClass.superFqn();
            pendingCalls.add(new PendingCall(fromSym, "<init>", eci.getArguments().size(),
                    owner, null, true, relPath, lineOf(eci)));
        }
    }

    // ─────────────────────────── Phase B：调用仲裁 ───────────────────────────

    /**
     * Lombok 访问器合成：类级 @Data/@Getter/@Setter/@Value 或字段级 @Getter/@Setter 时，
     * 为非 static 字段补 getter/setter def（Lombok 在编译期改 AST，处理器看得到合成方法，
     * 静态版镜像之，否则调用边全部断链且全局唯一仲裁被“显式手写 getter 唯一”污染）。
     * 本类显式声明遮蔽不合成；@Accessors(fluent=true) 无 get/set 前缀，跳过。
     */
    private void synthesizeLombokAccessors(FieldDeclaration fd, TypeDeclaration<?> td, ClassInfo ci,
                                           FileCtx ctx, String relPath, List<String> defLines) {
        if (fd.getVariables().isEmpty()
                || (td instanceof ClassOrInterfaceDeclaration
                    && ((ClassOrInterfaceDeclaration) td).isInterface())) {
            return; // 接口字段是常量，Lombok 不处理
        }
        for (Modifier m : fd.getModifiers()) {
            if (m.getKeyword() == Modifier.Keyword.STATIC) {
                return; // static 字段 Lombok 不生成访问器
            }
        }
        if (accessorsFluent(td) || accessorsFluent(fd)) {
            return;
        }
        boolean getter = hasLombokAnnotation(td, "Data", "Getter", "Value")
                || hasLombokAnnotation(fd, "Getter");
        boolean setter = hasLombokAnnotation(td, "Data", "Setter")
                || hasLombokAnnotation(fd, "Setter");
        if (!getter && !setter) {
            return;
        }
        boolean bareBool = fd.getElementType() instanceof PrimitiveType
                && ((PrimitiveType) fd.getElementType()).getType() == PrimitiveType.Primitive.BOOLEAN;
        for (VariableDeclarator vd : fd.getVariables()) {
            String field = vd.getNameAsString();
            String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            if (getter) {
                String m = (bareBool ? "is" : "get") + cap;
                emitSynthetic(ci, m, ci.fqn + "#" + m + "()", 0,
                        typeForSym(vd.getType(), ctx), vd, relPath, defLines);
            }
            if (setter) {
                String m = "set" + cap;
                emitSynthetic(ci, m, ci.fqn + "#" + m + "(" + typeForSym(vd.getType(), ctx) + ")",
                        1, null, vd, relPath, defLines);
            }
        }
    }

    /** 合成方法登记 + def 行（本类显式同名声明优先，不重复合成）。 */
    private void emitSynthetic(ClassInfo ci, String name, String sym, int params, String ret,
                               VariableDeclarator vd, String relPath, List<String> defLines) {
        if (ci.methods.containsKey(name)) {
            return;
        }
        ci.addSynthetic(name, sym, params, ret);
        registerGlobalMethod(name, sym, params);
        defLines.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(sym)
                + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + lineOf(vd.getName()) + "}");
    }

    /**
     * MapStruct 实现类合成：@Mapper（org.mapstruct.Mapper，按显式 import 精确判定——
     * mybatis 的同名注解是运行时代理不生成源码）接口在编译期生成 XxxImpl（target/generated-sources
     * 会被编译索引收录），静态版按命名约定镜像类 def + 抽象方法 def，供 symbol 搜索与全局唯一仲裁对齐。
     * default/static 方法不生成（MapStruct 只实现抽象方法）；仓内已有同名真实类时真实优先。
     */
    private void synthesizeMapStructImpl(ClassOrInterfaceDeclaration cd, String fqn,
                                         FileCtx ctx, String relPath, List<String> defLines) {
        // 通配 import org.mapstruct.* 同样命中（生产实测 IndividualConvertUtil 用通配导入，
        // 旧版仅认精确 import 导致整类 Impl 漏合成）；mybatis 同名注解在 org.apache.ibatis 包不冲突
        boolean mapperImport = "org.mapstruct.Mapper".equals(ctx.singleImports.get("Mapper"))
                || ctx.wildcardImports.contains("org.mapstruct");
        if (!cd.isInterface() || !mapperImport
                || !hasLombokAnnotation(cd, "Mapper")
                || classesByFqn.containsKey(fqn + "Impl")) {
            return;
        }
        String implFqn = fqn + "Impl";
        ClassInfo implCi = new ClassInfo(implFqn, ctx, false);
        classesByFqn.put(implFqn, implCi);
        mapperImplFiles.put(implFqn, relPath);
        defLines.add("{\"t\":\"def\",\"kind\":\"class\",\"sym\":\"" + esc(implFqn)
                + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + lineOf(cd.getName()) + "}");
        List<String[]> abstracts = new ArrayList<String[]>();
        mapperAbstractMethods.put(implFqn, abstracts);
        for (BodyDeclaration<?> member : cd.getMembers()) {
            if (!(member instanceof MethodDeclaration) || ((MethodDeclaration) member).getBody().isPresent()) {
                continue;
            }
            MethodDeclaration md = (MethodDeclaration) member;
            StringBuilder params = new StringBuilder();
            for (Parameter p : md.getParameters()) {
                if (params.length() > 0) {
                    params.append(',');
                }
                params.append(typeForSym(p.getType(), ctx));
                if (p.isVarArgs()) {
                    params.append("[]");
                }
            }
            String name = md.getNameAsString();
            String sym = implFqn + "#" + name + "(" + params + ")";
            String ret = retOf(md, ctx);
            implCi.addSynthetic(name, sym, md.getParameters().size(), ret);
            registerGlobalMethod(name, sym, md.getParameters().size());
            abstracts.add(new String[]{params.toString(), ret});
            defLines.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(sym)
                    + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + lineOf(md.getName()) + "}");
        }
    }

    /** implements/接口 extends 的类型解析：外部（仓内无声明）则入 externalInterfaces。 */
    private void collectExternalInterface(String dotted, FileCtx ctx) {
        String fqn = resolveDottedPrefix(dotted, ctx);
        if (fqn != null && !classesByFqn.containsKey(fqn)) {
            externalInterfaces.add(fqn);
        }
    }

    /** 仓内类字段类型表填充（MapStruct helper 合成用：源/目标字段 diff）。 */
    private void collectFieldTypes(String fqn, FieldDeclaration fd, FileCtx ctx) {
        Map<String, String> fields = classFieldTypes.get(fqn);
        if (fields == null) {
            fields = new HashMap<String, String>();
            classFieldTypes.put(fqn, fields);
        }
        for (VariableDeclarator vd : fd.getVariables()) {
            if (!fields.containsKey(vd.getNameAsString())) {
                fields.put(vd.getNameAsString(), typeForSym(vd.getType(), ctx));
            }
        }
    }

    /**
     * Lombok 构造器镜像（编译期生成源码被索引，生产实测 Operator#<init>() 等无源码 ctor def + super 边）：
     * @NoArgsConstructor → #<init>()；@AllArgsConstructor → 全字段；@RequiredArgsConstructor/@Data
     * → 必填字段（final 未初始化 + @NonNull，零必填 = 无参）；@Value 隐含全字段；@Builder 单独存在
     * 时隐含全字段 package-private。合成 ctor 体含隐式 super()（无参）→ 补 super 边。
     * 显式 ctor 存在时 @Data/@Value/@Builder 推导跳过；同 arity 显式 ctor 遮蔽。
     */
    private void synthesizeLombokCtors(TypeDeclaration<?> td, ClassInfo ci,
                                       FileCtx ctx, String relPath, List<String> defLines) {
        if (td instanceof com.github.javaparser.ast.body.EnumDeclaration || ci.isInterface) {
            return;
        }
        int explicitCtors = 0;
        int explicitNoArg = 0;
        int explicitMaxArity = -1;
        for (BodyDeclaration<?> m : td.getMembers()) {
            if (m instanceof ConstructorDeclaration) {
                explicitCtors++;
                int n = ((ConstructorDeclaration) m).getParameters().size();
                explicitMaxArity = Math.max(explicitMaxArity, n);
                if (n == 0) {
                    explicitNoArg++;
                }
            }
        }
        boolean noArgs = hasLombokAnnotation(td, "NoArgsConstructor");
        boolean allArgs = hasLombokAnnotation(td, "AllArgsConstructor");
        boolean reqArgs = hasLombokAnnotation(td, "RequiredArgsConstructor");
        boolean dataCtor = hasLombokAnnotation(td, "Data") && explicitCtors == 0
                && !noArgs && !allArgs && !reqArgs;
        boolean valueCtor = hasLombokAnnotation(td, "Value") && explicitCtors == 0
                && !noArgs && !allArgs && !reqArgs;
        boolean builderCtor = hasLombokAnnotation(td, "Builder") && explicitCtors == 0
                && !noArgs && !allArgs && !reqArgs && !dataCtor && !valueCtor;
        if (!noArgs && !allArgs && !reqArgs && !dataCtor && !valueCtor && !builderCtor) {
            return;
        }
        // 字段清单（实例字段，声明序）：[渲染类型]，另记必填子集（final 未初始化 / @NonNull）
        List<String> allParams = new ArrayList<String>();
        List<String> requiredParams = new ArrayList<String>();
        for (BodyDeclaration<?> m : td.getMembers()) {
            if (!(m instanceof FieldDeclaration)) {
                continue;
            }
            FieldDeclaration fd = (FieldDeclaration) m;
            boolean isStatic = false;
            for (Modifier mod : fd.getModifiers()) {
                if (mod.getKeyword() == Modifier.Keyword.STATIC) {
                    isStatic = true;
                }
            }
            if (isStatic) {
                continue;
            }
            boolean required = fd.isFinal() || hasLombokAnnotation(fd, "NonNull");
            for (VariableDeclarator vd : fd.getVariables()) {
                String t = typeForSym(vd.getType(), ctx);
                if (t == null) {
                    return; // 类型解析失败：构造器签名不完整，整类跳过（宁缺勿错）
                }
                allParams.add(t);
                if (required && !vd.getInitializer().isPresent()) {
                    requiredParams.add(t);
                }
            }
        }
        // 无参形态：@NoArgsConstructor 显式，或 @Data 零必填（@RequiredArgsConstructor 0 参）
        int classLine = lineOf(td.getName());
        if ((noArgs || reqArgs && requiredParams.isEmpty()
                || dataCtor && requiredParams.isEmpty()) && explicitNoArg == 0) {
            emitSyntheticCtor(ci, relPath, defLines, classLine, new ArrayList<String>());
        }
        // 全字段形态：@AllArgsConstructor / @Value 隐含 / @Builder 单独隐含 / @RequiredArgsConstructor 非零必填
        List<String> full = null;
        if (allArgs || valueCtor || builderCtor) {
            full = allParams;
        } else if (reqArgs && !requiredParams.isEmpty()) {
            full = requiredParams;
        } else if (dataCtor && !requiredParams.isEmpty()) {
            full = requiredParams;
        }
        if (full != null && full.size() != explicitMaxArity) {
            emitSyntheticCtor(ci, relPath, defLines, classLine, full);
        }
    }

    /** 合成构造器：def + ClassInfo 登记 + 隐式 super() 边（无参形态，同显式 ctor 规则）。 */
    private void emitSyntheticCtor(ClassInfo ci, String relPath, List<String> defLines,
                                   int line, List<String> params) {
        StringBuilder sb = new StringBuilder();
        for (String p : params) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        String sym = ci.fqn + "#<init>(" + sb + ")";
        ci.addSynthetic("<init>", sym, params.size(), null);
        defLines.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(sym)
                + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + line + "}");
        String superFqn = ci.superRaw == null ? "java.lang.Object" : ci.superFqn();
        if (superFqn != null) {
            pendingCalls.add(new PendingCall(sym, "<init>", 0, superFqn, null, true, relPath, line));
        }
    }

    /**
     * @Builder 镜像（编译期生成源码被索引，生产实测 Xxx#builder() + Xxx.XxxBuilder 嵌套类
     * + 每字段 fluent 方法 + build()）：builder() 返回 Builder（链式续链），fluent 方法返回
     * Builder 自身，build() 返回外部类。仓内真实同名 Builder 类优先；@SuperBuilder 形态不同跳过。
     */
    private void synthesizeBuilder(ClassOrInterfaceDeclaration cd, ClassInfo ci,
                                   FileCtx ctx, String relPath, List<String> defLines) {
        if (cd.isInterface() || !hasLombokAnnotation(cd, "Builder")) {
            return;
        }
        String simple = cd.getNameAsString();
        String builderFqn = ci.fqn + "." + simple + "Builder";
        if (classesByFqn.containsKey(builderFqn)) {
            return; // 真实同名类优先
        }
        ClassInfo bci = new ClassInfo(builderFqn, ctx, false);
        classesByFqn.put(builderFqn, bci);
        int line = lineOf(cd.getName());
        defLines.add("{\"t\":\"def\",\"kind\":\"class\",\"sym\":\"" + esc(builderFqn)
                + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + line + "}");
        String builderMethod = ci.fqn + "#builder()";
        ci.addSynthetic("builder", builderMethod, 0, builderFqn);
        defLines.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(builderMethod)
                + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + line + "}");
        for (BodyDeclaration<?> m : cd.getMembers()) {
            if (!(m instanceof FieldDeclaration)) {
                continue;
            }
            FieldDeclaration fd = (FieldDeclaration) m;
            boolean isStatic = false;
            for (Modifier mod : fd.getModifiers()) {
                if (mod.getKeyword() == Modifier.Keyword.STATIC) {
                    isStatic = true;
                }
            }
            if (isStatic) {
                continue;
            }
            for (VariableDeclarator vd : fd.getVariables()) {
                String t = typeForSym(vd.getType(), ctx);
                if (t == null) {
                    continue;
                }
                String name = vd.getNameAsString();
                String sym = builderFqn + "#" + name + "(" + t + ")";
                bci.addSynthetic(name, sym, 1, builderFqn);
                defLines.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(sym)
                        + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + line + "}");
            }
        }
        String build = builderFqn + "#build()";
        bci.addSynthetic("build", build, 0, ci.fqn);
        defLines.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(build)
                + "\",\"file\":\"" + esc(relPath) + "\",\"line\":" + line + "}");
    }

    /**
     * MapStruct xToY 辅助方法合成（生产实测形态：Impl 内私有 helper，接口无声明——
     *  sdkPrintHerbsDTOToPrintHerbsDTO(SdkPrintHerbsDTO)、List 字段变体
     *  xListToYList(java.util.List<X>)；命名 = decap(源类型简名) + "To" + 目标类型简名）。
     *  推导：mapper 抽象方法 S→T 的源/目标字段表 diff（同名不同型字段对），
     *  MapStruct 内置可转类型（String/装箱/时间）不生成 helper → 黑名单。
     */
    private void synthesizeMapStructHelpers(List<String> defLines) {
        for (Map.Entry<String, List<String[]>> e : mapperAbstractMethods.entrySet()) {
            String implFqn = e.getKey();
            ClassInfo implCi = classesByFqn.get(implFqn);
            String relPath = mapperImplFiles.get(implFqn);
            if (implCi == null || relPath == null) {
                continue;
            }
            // (helper sym → ret)，同 Impl 内按 sym 去重（多方法重复命中同一字段对）
            Map<String, String> helpers = new java.util.LinkedHashMap<String, String>();
            for (String[] m : e.getValue()) {
                String s = m[0];
                String t = m[1];
                if (s == null || t == null || s.isEmpty() || s.indexOf(',') >= 0
                        || s.endsWith("[]") || s.indexOf('<') >= 0 || s.indexOf('.') < 0) {
                    continue; // 多源参/数组/泛型源/裸名源：保守跳过
                }
                Map<String, String> fs = classFieldTypes.get(s);
                Map<String, String> ft = classFieldTypes.get(stripGenerics(t));
                if (fs == null || ft == null) {
                    continue;
                }
                for (Map.Entry<String, String> fe : fs.entrySet()) {
                    String ta = fe.getValue();
                    String tb = ft.get(fe.getKey());
                    if (ta == null || tb == null) {
                        continue;
                    }
                    collectHelper(implFqn, ta, tb, helpers);
                }
            }
            for (Map.Entry<String, String> h : helpers.entrySet()) {
                String sym = h.getKey();
                String name = sym.substring(implFqn.length() + 1, sym.indexOf('('));
                implCi.addSynthetic(name, sym, 1, h.getValue());
                registerGlobalMethod(name, sym, 1);
                defLines.add("{\"t\":\"def\",\"kind\":\"method\",\"sym\":\"" + esc(sym)
                        + "\",\"file\":\"" + esc(relPath) + "\",\"line\":1}");
            }
        }
    }

    /** 单个字段对的 helper 收集：同名不同型且非内置可转 → 单数/列表变体签名
     *  （List 容器形态须先于剥泛型同型短路：List&lt;X&gt; vs List&lt;Y&gt; 剥后同为 java.util.List）。 */
    private void collectHelper(String implFqn, String ta, String tb, Map<String, String> out) {
        String ra = stripGenerics(ta);
        String rb = stripGenerics(tb);
        if ("java.util.List".equals(ra) && "java.util.List".equals(rb)) {
            String[] ea = typeArgsOf(ta);
            String[] eb = typeArgsOf(tb);
            if (ea != null && eb != null && ea.length == 1 && eb.length == 1
                    && !stripGenerics(ea[0]).equals(stripGenerics(eb[0]))
                    && !builtinConvertible(stripGenerics(ea[0]))
                    && !builtinConvertible(stripGenerics(eb[0]))) {
                String name = decap(simpleNameOf(stripGenerics(ea[0]))) + "ListTo"
                        + simpleNameOf(stripGenerics(eb[0])) + "List";
                out.put(implFqn + "#" + name + "(java.util.List<" + ea[0] + ">)",
                        "java.util.List<" + eb[0] + ">");
            }
            return;
        }
        if (ra.equals(rb) || builtinConvertible(ra) || builtinConvertible(rb)) {
            return;
        }
        if (ta.indexOf('<') >= 0 || tb.indexOf('<') >= 0 || ta.indexOf('[') >= 0 || tb.indexOf('[') >= 0) {
            return; // Map/Set 等容器字段形态无生产实证，保守跳过
        }
        String name = decap(simpleNameOf(ra)) + "To" + simpleNameOf(rb);
        out.put(implFqn + "#" + name + "(" + ta + ")", tb);
    }

    /** MapStruct 内置可转类型（生成器不产 helper）：装箱/字符串/数值/时间/Date。 */
    private static boolean builtinConvertible(String fqn) {
        if (fqn.startsWith("java.lang.") || fqn.startsWith("java.math.")
                || fqn.startsWith("java.time.") || "java.util.Date".equals(fqn)
                || "boolean".equals(fqn) || "int".equals(fqn) || "long".equals(fqn)
                || "double".equals(fqn) || "float".equals(fqn) || "short".equals(fqn)
                || "byte".equals(fqn) || "char".equals(fqn)) {
            return true;
        }
        return false;
    }

    /** 简名（嵌套点分 FQN 取末段：com.x.Outer.Inner → Inner）。 */
    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** 首字母小写（MapStruct helper 命名：SdkPrintHerbsDTO → sdkPrintHerbsDTO）。 */
    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** 匿名类 super 类型是否接口：仓内看声明；外部看全仓 implements/接口 extends 聚合
     *  （多方信息，免 jar）；JDK 常用函数式接口硬编码。 */
    private boolean isProbablyInterface(String fqn) {
        ClassInfo ci = classesByFqn.get(fqn);
        if (ci != null) {
            return ci.isInterface;
        }
        if (externalInterfaces.contains(fqn)) {
            return true;
        }
        return JDK_FUNC_INTERFACES.contains(fqn);
    }

    /** JDK 常用函数式/集合接口（匿名类高频 super）。 */
    private static final Set<String> JDK_FUNC_INTERFACES = new HashSet<String>(Arrays.asList(
            "java.lang.Runnable", "java.util.concurrent.Callable", "java.util.Comparator",
            "java.util.function.Function", "java.util.function.Consumer",
            "java.util.function.Predicate", "java.util.function.Supplier",
            "java.util.function.BiFunction", "java.util.function.BiConsumer",
            "java.util.function.BiPredicate", "java.util.function.UnaryOperator",
            "java.util.function.BinaryOperator", "java.io.FileFilter", "java.io.FilenameFilter"));

    /** 裸注解名命中（@Data / lombok.Data 均识别）。 */
    private static boolean hasLombokAnnotation(NodeWithAnnotations<?> n, String... names) {
        for (com.github.javaparser.ast.expr.AnnotationExpr a : n.getAnnotations()) {
            String s = a.getNameAsString();
            int dot = s.lastIndexOf('.');
            String bare = dot < 0 ? s : s.substring(dot + 1);
            for (String nm : names) {
                if (nm.equals(bare)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @Accessors(fluent=true)：访问器无 get/set 前缀，无法按命名规则合成，跳过。 */
    private static boolean accessorsFluent(NodeWithAnnotations<?> n) {
        for (com.github.javaparser.ast.expr.AnnotationExpr a : n.getAnnotations()) {
            if (a.getNameAsString().endsWith("Accessors")) {
                for (MemberValuePair p : a.findAll(MemberValuePair.class)) {
                    if ("fluent".equals(p.getNameAsString())
                            && "true".equals(p.getValue().toString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 类链（本类沿仓内父类链）是否声明了该方法名（名字级，A1 已全量登记）。 */
    private boolean declaredInHierarchy(ClassInfo ci, String name) {
        ClassInfo cur = ci;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.methods.containsKey(name)) {
                return true;
            }
            String superFqn = cur.superFqn();
            cur = superFqn == null ? null : classesByFqn.get(superFqn);
        }
        return false;
    }

    /** 静态通配导入中唯一声明了该方法的仓内类；0 个或多个返回 null（宁缺勿错）。 */
    private String pickStaticWildcardOwner(FileCtx ctx, String name) {
        ClassInfo hit = null;
        for (String owner : ctx.staticWildcardOwners) {
            ClassInfo ci = classesByFqn.get(owner);
            if (ci != null && ci.methods.containsKey(name)) {
                if (hit != null) {
                    return null;
                }
                hit = ci;
            }
        }
        return hit == null ? null : hit.fqn;
    }

    private String resolveCallTarget(PendingCall pc) {
        if (pc.isCtor) {
            ClassInfo ci = pc.ownerHint == null ? null : classesByFqn.get(pc.ownerHint);
            if (ci == null) {
                // 外部构造器：JDK/guava 表命中带擦除签名（java.util.ArrayList#<init>(java.util.Collection<? extends E>)），
                // 其余无参签名（编译期同样只有边无 def）
                String sig = pc.ownerHint == null ? null : jdkSignature(pc.ownerHint, "<init>", pc.argc, null);
                return sig != null ? sig
                        : (pc.ownerHint == null ? null : pc.ownerHint + "#<init>()");
            }
            String sig = pickByArity(ci, "<init>", pc.argc, pc.argTypes);
            // 隐式默认构造器（无源码声明）：编译期同样有边无 def，镜像之
            return sig != null ? sig : ci.fqn + "#<init>()";
        }
        if (pc.ownerHint != null) {
            ClassInfo ci = classesByFqn.get(pc.ownerHint);
            if (ci == null) {
                // 外部依赖（类 FQN 来自 import——import 本身就是完整信息源，无需 jar）：
                // JDK/guava 内置表命中 → 声明处擦除签名边（对齐生产 TypeMirror.toString）；
                // 其余按调用点信息渲染：无参 → "()"（公司 DTO getter 场景与生产一致）；
                // 带参且实参类型全部可知 → 完整签名（RPC client 调用等）；否则裸名展示边
                String sig = jdkSignature(pc.ownerHint, pc.calleeName, pc.argc, pc.argTypes);
                if (sig != null) {
                    return sig;
                }
                if (pc.argc == 0) {
                    return pc.ownerHint + "#" + pc.calleeName + "()";
                }
                if (pc.argTypes != null && pc.argTypes.length == pc.argc) {
                    StringBuilder params = new StringBuilder();
                    for (int i = 0; i < pc.argTypes.length; i++) {
                        if (pc.argTypes[i] == null) {
                            params = null;
                            break;
                        }
                        if (params.length() > 0) {
                            params.append(',');
                        }
                        params.append(pc.argTypes[i]);
                    }
                    if (params != null) {
                        return pc.ownerHint + "#" + pc.calleeName + "(" + params + ")";
                    }
                }
                return pc.ownerHint + "#" + pc.calleeName;
            }
            String sym = findMethodInHierarchy(ci, pc.calleeName, pc.argc, pc.argTypes);
            if (sym != null) {
                return sym;
            }
            // 仓内类但方法未声明（继承自外部父类等）：退化展示性边
            return pc.ownerHint + "#" + pc.calleeName;
        }
        // 无接收者线索：全局唯一同名仲裁（先参数个数精确，再任意；歧义丢弃宁缺勿错）
        List<MethodSig> any = globalMethodsByName.get(pc.calleeName);
        if (any == null) {
            return null;
        }
        List<MethodSig> exact = new ArrayList<MethodSig>();
        for (MethodSig m : any) {
            if (m.params == pc.argc) {
                exact.add(m);
            }
        }
        if (exact.size() == 1) {
            return exact.get(0).sym;
        }
        if (exact.isEmpty() && any.size() == 1) {
            return any.get(0).sym;
        }
        return null;
    }

    /** 沿父类链（仓内）找方法：名字 + 参数个数命中；同名同 arity 重载按实参类型消歧。 */
    private String findMethodInHierarchy(ClassInfo ci, String name, int argc, String[] argTypes) {
        ClassInfo cur = ci;
        for (int i = 0; i < 8 && cur != null; i++) {
            String sym = pickByArity(cur, name, argc, argTypes);
            if (sym != null) {
                return sym;
            }
            String superFqn = cur.superFqn();
            cur = superFqn == null ? null : classesByFqn.get(superFqn);
        }
        return null;
    }

    /** 类内按名字取签名：按参数个数命中；同名同 arity 重载按实参静态类型消歧
     *  （多方信息聚合：scope 局部变量/字面量类型即完整信息，免 jar 选对重载——
     *  生产实证 adapter 有 4 个单参 provide 重载，旧逻辑“返回首个声明”选错）；
     *  消歧失败回退首个声明；未声明返回 null；arity 不匹配返回首个声明。 */
    private String pickByArity(ClassInfo ci, String name, int argc, String[] argTypes) {
        List<MethodSig> sigs = ci.methods.get(name);
        if (sigs == null || sigs.isEmpty()) {
            return null;
        }
        List<MethodSig> hit = null;
        for (MethodSig m : sigs) {
            if (m.params == argc) {
                if (hit == null) {
                    hit = new ArrayList<MethodSig>();
                }
                hit.add(m);
            }
        }
        if (hit != null) {
            if (hit.size() == 1) {
                return hit.get(0).sym;
            }
            String byTypes = pickByArgTypes(hit, argTypes);
            return byTypes != null ? byTypes : hit.get(0).sym;
        }
        return sigs.get(0).sym;
    }

    /** 同名同 arity 候选按实参类型消歧：候选形参（sym 括号内顶层切分，剥泛型与 TYPE_USE
     *  注解前缀）与实参（剥泛型）逐位相等；唯一完全匹配返回其 sym，0 个或多个匹配返回 null。 */
    private static String pickByArgTypes(List<MethodSig> cands, String[] argTypes) {
        if (argTypes == null || argTypes.length == 0) {
            return null;
        }
        String found = null;
        for (MethodSig m : cands) {
            if (m.sym == null) {
                continue;
            }
            String[] params = splitParams(m.sym);
            if (params == null || params.length != argTypes.length) {
                continue;
            }
            boolean all = true;
            for (int i = 0; i < params.length; i++) {
                if (argTypes[i] == null || !paramRaw(params[i]).equals(stripGenerics(argTypes[i]))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                if (found != null) {
                    return null; // 多候选同形（泛型擦除后一致），不冒险
                }
                found = m.sym;
            }
        }
        return found;
    }

    /** 形参渲染剥壳：TYPE_USE 注解形态 (@Fqn :: T) 取 T；再剥泛型。 */
    private static String paramRaw(String p) {
        String s = p.trim();
        if (s.startsWith("(@")) {
            int sep = s.indexOf(" :: ");
            int end = s.lastIndexOf(')');
            if (sep >= 0 && end > sep) {
                s = s.substring(sep + 4, end);
            }
        }
        return stripGenerics(s);
    }

    /** sym 参数列表顶层切分（#name(...) 括号内；泛型/注解嵌套深度计数；无参空数组）。 */
    private static String[] splitParams(String sym) {
        int lp = sym.indexOf('(');
        int rp = sym.lastIndexOf(')');
        if (lp < 0 || rp <= lp) {
            return null;
        }
        String inner = sym.substring(lp + 1, rp);
        if (inner.isEmpty()) {
            return new String[0];
        }
        List<String> parts = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts.toArray(new String[0]);
    }

    // ─────────────────────────── 类型解析 ───────────────────────────

    /** 接收者表达式 → 类型 FQN（owner 仲裁口径：剥泛型裸 FQN），未知 null。 */
    private String resolveOwner(Optional<Expression> scopeOpt, FileCtx ctx,
                                ClassInfo currentClass, VarScope scope) {
        if (!scopeOpt.isPresent()) {
            return currentClass.fqn;
        }
        String t = resolveTypeOf(scopeOpt.get(), ctx, currentClass, scope);
        return t == null ? null : stripGenerics(t);
    }

    /** 接收者表达式 → 完整类型（带泛型实参；lambda 元素提取与链式泛型代入用），未知 null。 */
    private String resolveTypeOf(Expression e, FileCtx ctx, ClassInfo currentClass, VarScope scope) {
        if (e instanceof FieldAccessExpr) {
            FieldAccessExpr fa = (FieldAccessExpr) e;
            // this.field → 字段类型（scope 存完整类型带泛型）
            if (fa.getScope() instanceof ThisExpr
                    && scope != null && scope.vars.containsKey(fa.getNameAsString())) {
                return scope.vars.get(fa.getNameAsString());
            }
            // A.B.c / 常量链：从最长前缀开始尝试类名解析；剩余段非嵌套类型时
            // （枚举常量 EnumName.CONSTANT / 静态字段 SomeClass.INSTANCE 接收者）收敛到类本身（对齐编译期挂类）
            String dotted = dottedName(fa);
            if (dotted != null) {
                return resolveOwnerDotted(dotted, ctx);
            }
            return null;
        }
        if (e instanceof NameExpr) {
            String n = ((NameExpr) e).getNameAsString();
            if (scope != null && scope.vars.containsKey(n)) {
                return scope.vars.get(n);
            }
            return resolveSimpleName(n, ctx);
        }
        if (e instanceof ThisExpr) {
            return currentClass.fqn;
        }
        if (e instanceof SuperExpr) {
            return currentClass.superFqn();
        }
        if (e instanceof ObjectCreationExpr) {
            return typeForSym(((ObjectCreationExpr) e).getType(), ctx);
        }
        if (e instanceof CastExpr) {
            return typeForSym(((CastExpr) e).getType(), ctx);
        }
        if (e instanceof EnclosedExpr) {
            return resolveTypeOf(((EnclosedExpr) e).getInner(), ctx, currentClass, scope);
        }
        if (e instanceof MethodCallExpr) {
            return chainReturnType((MethodCallExpr) e, ctx, currentClass, scope);
        }
        return null;
    }

    /** 调用点接收者 owner（与 Phase B 仲裁同一口径）：本类链声明优先 → 精确静态导入 →
     *  静态通配唯一声明者 → 表达式解析；链式推断与调用收集共用。 */
    private String ownerOfCall(MethodCallExpr mce, FileCtx ctx, ClassInfo currentClass, VarScope scope) {
        if (!mce.getScope().isPresent()) {
            // 裸调用 = this 语义：本类（沿父类链）优先；其次精确静态导入；静态通配仅当
            // owner 确实声明了该方法才挂（旧逻辑 get(0) 会把通配导入文件里的任意裸调用
            // 错挂到第一个通配 owner）；均未命中交全局唯一仲裁（宁缺勿错）
            if (declaredInHierarchy(currentClass, mce.getNameAsString())) {
                return currentClass.fqn;
            }
            String so = ctx.staticMethods.get(mce.getNameAsString());
            return so != null ? so : pickStaticWildcardOwner(ctx, mce.getNameAsString());
        }
        return resolveOwner(mce.getScope(), ctx, currentClass, scope);
    }

    /**
     * 链式接收者类型推断：a().b() 的 b 类型 = a() 返回类型（完整带泛型）。
     * 仓内方法沿父类链查 MethodSig.ret（声明处泛型实参）；JDK/guava 查内置表返回模板并代入
     * 接收者泛型实参（List&lt;X&gt;.stream() → Stream&lt;X&gt;）；collect(Collectors.toList()) 特判 List&lt;E&gt;。
     * 未映射占位符（Stream#map 的 R）返回 null——宁缺勿错，链断而非错边。
     */
    private String chainReturnType(MethodCallExpr mce, FileCtx ctx, ClassInfo currentClass, VarScope scope) {
        String owner = ownerOfCall(mce, ctx, currentClass, scope);
        if (owner == null) {
            return null;
        }
        String name = mce.getNameAsString();
        int argc = mce.getArguments().size();
        ClassInfo ci = classesByFqn.get(owner);
        if (ci != null) {
            return retOfMethodInHierarchy(ci, name, argc, argTypesOf(mce, ctx, currentClass, scope));
        }
        // 外部 getter 对偶推断：同仓 setter 实参类型即字段类型（多方信息聚合，免 jar 续链）
        Map<String, String> fields = externalFieldTypes.get(owner);
        if (fields != null && argc == 0
                && (name.startsWith("get") && name.length() > 3
                    || name.startsWith("is") && name.length() > 2)) {
            String field = name.startsWith("get")
                    ? Character.toLowerCase(name.charAt(3)) + name.substring(4)
                    : Character.toLowerCase(name.charAt(2)) + name.substring(3);
            String t = fields.get(field);
            if (t != null) {
                return t;
            }
        }
        // JDK/guava 内置表：collect(toList()/toSet()) 返回类型由 Collector 决定，表无法表达 → 特判
        if ("collect".equals(name) && argc == 1 && mce.getScope().isPresent()) {
            String coll = collectorsCallName(mce.getArguments().get(0), ctx);
            if ("toList".equals(coll) || "toSet".equals(coll)) {
                String elem = elemTypeOf(mce.getScope().get(), ctx, currentClass, scope);
                return elem == null ? null
                        : ("toList".equals(coll) ? "java.util.List<" : "java.util.Set<") + elem + ">";
            }
        }
        String[] entry = jdkMethodEntry(owner, name, argc, argTypesOf(mce, ctx, currentClass, scope));
        if (entry == null || entry[3] == null) {
            return null;
        }
        String recvFull = mce.getScope().isPresent()
                ? resolveTypeOf(mce.getScope().get(), ctx, currentClass, scope) : owner;
        return substituteTypeParams(entry[3], recvFull != null ? recvFull : owner);
    }

    /** 沿父类链查方法返回类型（名字+arity 命中；同名同 arity 重载按实参类型消歧；
     *  消歧失败回退首个非 null ret；声明处泛型实参渲染）。 */
    private String retOfMethodInHierarchy(ClassInfo ci, String name, int argc, String[] argTypes) {
        ClassInfo cur = ci;
        for (int i = 0; i < 8 && cur != null; i++) {
            List<MethodSig> sigs = cur.methods.get(name);
            if (sigs != null) {
                List<MethodSig> hit = new ArrayList<MethodSig>();
                for (MethodSig m : sigs) {
                    if (m.params == argc) {
                        hit.add(m);
                    }
                }
                MethodSig chosen = null;
                if (hit.size() == 1) {
                    chosen = hit.get(0);
                } else if (hit.size() > 1) {
                    String sym = pickByArgTypes(hit, argTypes);
                    if (sym != null) {
                        for (MethodSig m : hit) {
                            if (sym.equals(m.sym)) {
                                chosen = m;
                                break;
                            }
                        }
                    }
                }
                if (chosen != null && chosen.ret != null) {
                    return chosen.ret;
                }
                for (MethodSig m : hit) {
                    if (m.ret != null) {
                        return m.ret;
                    }
                }
            }
            String superFqn = cur.superFqn();
            cur = superFqn == null ? null : classesByFqn.get(superFqn);
        }
        return null;
    }

    /** JDK/guava 方法表条目（owner 沿 JDK_HIERARCHY 上溯到声明类；同名同 arity 重载按实参数组
     *  形态消歧；未命中 null）。[name, argc, sig, ret]。 */
    private String[] jdkMethodEntry(String owner, String name, int argc, String[] argTypes) {
        String cur = owner;
        for (int i = 0; i < 6 && cur != null; i++) {
            List<String[]> l = JDK_METHODS.get(cur);
            if (l != null) {
                List<String[]> cands = new ArrayList<String[]>();
                for (String[] m : l) {
                    if (m[0].equals(name) && Integer.parseInt(m[1]) == argc) {
                        cands.add(m);
                    }
                }
                if (!cands.isEmpty()) {
                    return pickJdkOverload(cands, argTypes);
                }
            }
            cur = JDK_HIERARCHY.get(cur);
        }
        return null;
    }

    /** JDK/guava 边签名（声明处擦除格式，对齐生产 TypeMirror.toString）：owner 沿层级上溯，
     *  命中返回 decl#sig（List#forEach → java.lang.Iterable#forEach(java.util.function.Consumer<? super T>)）；未命中 null。 */
    private String jdkSignature(String owner, String name, int argc, String[] argTypes) {
        String cur = owner;
        for (int i = 0; i < 6 && cur != null; i++) {
            List<String[]> l = JDK_METHODS.get(cur);
            if (l != null) {
                List<String[]> cands = new ArrayList<String[]>();
                for (String[] m : l) {
                    if (m[0].equals(name) && Integer.parseInt(m[1]) == argc) {
                        cands.add(m);
                    }
                }
                if (!cands.isEmpty()) {
                    String[] m = pickJdkOverload(cands, argTypes);
                    return cur + "#" + m[2];
                }
            }
            cur = JDK_HIERARCHY.get(cur);
        }
        return null;
    }

    /** 同名同 arity 重载消歧：实参非数组且条目数组形态互斥时排除数组条目
     *  （newArrayList(E[]) vs newArrayList(Iterable)——实参 List 时选 Iterable 版，对齐 javac 重载解析）；
     *  无法消歧取首条（低频重载，宁保守）。 */
    private static String[] pickJdkOverload(List<String[]> cands, String[] argTypes) {
        if (cands.size() == 1) {
            return cands.get(0);
        }
        if (argTypes != null && argTypes.length == 1 && argTypes[0] != null && !argTypes[0].endsWith("[]")) {
            List<String[]> nonArray = new ArrayList<String[]>();
            for (String[] m : cands) {
                String p = jdkParamRaw(m);
                if (!p.endsWith("[]")) {
                    nonArray.add(m);
                }
            }
            if (nonArray.size() == 1) {
                return nonArray.get(0);
            }
        }
        return cands.get(0);
    }

    /** 表条目首参 raw（剥泛型；如 "newArrayList(E[])" → "E[]"）。 */
    private static String jdkParamRaw(String[] m) {
        String sig = m[2];
        int p = sig.indexOf('(');
        return stripGenerics(sig.substring(p + 1, sig.length() - 1));
    }

    /** 实参是否 Collectors.xxx() 静态调用（显式 Collectors.toList() / 精确静态导入 / 通配静态导入），
     *  是则返回方法名，否则 null。 */
    private String collectorsCallName(Expression e, FileCtx ctx) {
        if (!(e instanceof MethodCallExpr)) {
            return null;
        }
        MethodCallExpr m = (MethodCallExpr) e;
        String owner;
        if (m.getScope().isPresent() && m.getScope().get() instanceof NameExpr) {
            owner = resolveSimpleName(((NameExpr) m.getScope().get()).getNameAsString(), ctx);
        } else if (!m.getScope().isPresent()) {
            owner = ctx.staticMethods.get(m.getNameAsString());
            if (owner == null) {
                owner = pickStaticWildcardOwner(ctx, m.getNameAsString());
            }
        } else {
            return null;
        }
        return "java.util.stream.Collectors".equals(owner) ? m.getNameAsString() : null;
    }

    /** 表达式泛型元素类型（接收者 List&lt;X&gt;/Stream&lt;X&gt; 的 X；未知 null）。 */
    private String elemTypeOf(Expression e, FileCtx ctx, ClassInfo currentClass, VarScope scope) {
        String full = resolveTypeOf(e, ctx, currentClass, scope);
        if (full == null) {
            return null;
        }
        String[] args = typeArgsOf(full);
        return args != null && args.length == 1 ? args[0] : null;
    }

    /**
     * lambda 参数类型绑定（多方信息聚合的关键一环）：lambda 作为集合/Stream/Optional/Map 方法实参时，
     * 参数类型 = 接收者泛型元素类型（forEach(x -> x.getA()) 的 x = List&lt;X&gt; 的元素 X）。
     * 预扫描注入 scope（在调用收集前），lambda 体内调用即可解析接收者；
     * 无法绑定（接收者泛型未知/参数已显式带类型）则跳过（宁缺勿错）。
     */
    private void bindLambdaParams(com.github.javaparser.ast.Node node, FileCtx ctx,
                                  ClassInfo currentClass, VarScope scope) {
        for (MethodCallExpr mce : node.findAll(MethodCallExpr.class)) {
            if (!mce.getScope().isPresent()) {
                continue;
            }
            for (Expression arg : mce.getArguments()) {
                if (!(arg instanceof LambdaExpr)) {
                    continue;
                }
                LambdaExpr lam = (LambdaExpr) arg;
                String recvFull = resolveTypeOf(mce.getScope().get(), ctx, currentClass, scope);
                if (recvFull == null) {
                    continue;
                }
                String[] elem = lambdaParamTypes(recvFull, mce.getNameAsString());
                if (elem == null || elem.length != lam.getParameters().size()) {
                    continue;
                }
                for (int i = 0; i < elem.length; i++) {
                    Parameter p = lam.getParameters().get(i);
                    if (elem[i] != null && p.getType() instanceof com.github.javaparser.ast.type.UnknownType) {
                        scope.put(p.getNameAsString(), elem[i]);
                    }
                }
            }
        }
    }

    /** lambda 参数类型（接收者完整类型 + 方法名 → 各参数类型）：Iterable/Stream/Optional 单参 T、
     *  Map 双参 K,V、Comparator lambda（sorted/max/min）双参 T,T；无法推导 null。 */
    private String[] lambdaParamTypes(String recvFull, String methodName) {
        String raw = stripGenerics(recvFull);
        String[] args = typeArgsOf(recvFull);
        String[] names = JDK_TYPE_PARAMS.get(raw);
        if (args == null || names == null || args.length != names.length) {
            return null;
        }
        if ("forEach".equals(methodName)) {
            return names.length == 2 ? new String[]{args[0], args[1]} : new String[]{args[0]};
        }
        if (("sorted".equals(methodName) || "max".equals(methodName) || "min".equals(methodName))
                && names.length == 1) {
            return new String[]{args[0], args[0]}; // Comparator lambda 双参 (T,T)
        }
        return names.length == 1 ? new String[]{args[0]} : null;
    }

    /** FieldAccessExpr 的点链（仅 Name/FieldAccess 链，this 起点返回 null）。 */
    private String dottedName(FieldAccessExpr fa) {
        StringBuilder sb = new StringBuilder(fa.getNameAsString());
        Expression cur = fa.getScope();
        while (true) {
            if (cur instanceof NameExpr) {
                return ((NameExpr) cur).getNameAsString() + "." + sb;
            }
            if (cur instanceof FieldAccessExpr) {
                sb.insert(0, ((FieldAccessExpr) cur).getNameAsString() + ".");
                cur = ((FieldAccessExpr) cur).getScope();
                continue;
            }
            return null;
        }
    }

    /** a.b.c：从最长前缀到最短尝试类名解析（兼容嵌套类引用 Outer.Inner）。 */
    private String resolveDottedPrefix(String dotted, FileCtx ctx) {
        String[] segs = dotted.split("\\.");
        for (int len = segs.length; len >= 1; len--) {
            String p = joinSegs(segs, len);
            String resolved = resolveSimpleName(p, ctx);
            if (resolved != null) {
                StringBuilder full = new StringBuilder(resolved);
                for (int i = len; i < segs.length; i++) {
                    full.append('.').append(segs[i]);
                }
                return full.toString();
            }
        }
        return null;
    }

    /** 接收者点链：与 resolveDottedPrefix 同构，但剩余段非嵌套类型时（枚举常量/静态字段）收敛到类本身。 */
    private String resolveOwnerDotted(String dotted, FileCtx ctx) {
        String[] segs = dotted.split("\\.");
        for (int len = segs.length; len >= 1; len--) {
            String resolved = resolveSimpleName(joinSegs(segs, len), ctx);
            if (resolved != null) {
                StringBuilder full = new StringBuilder(resolved);
                for (int i = len; i < segs.length; i++) {
                    full.append('.').append(segs[i]);
                }
                return classesByFqn.containsKey(full.toString()) ? full.toString() : resolved;
            }
        }
        return null;
    }

    private static String joinSegs(String[] segs, int len) {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (p.length() > 0) {
                p.append('.');
            }
            p.append(segs[i]);
        }
        return p.toString();
    }

    /** 简单类名 → FQN：同文件 → 单 import → 显式限定名（java./javax. 前缀）→ java.lang → 同包（A1 全量注册后精确确认）
     * → 通配 import（仓内类 / JDK 常用类按包前缀补全）→ 全仓唯一显式 import → 全局唯一简名；解析不了返回 null。 */
    private String resolveSimpleName(String name, FileCtx ctx) {
        String t = ctx.fileTypes.get(name);
        if (t != null) {
            return t;
        }
        t = ctx.singleImports.get(name);
        if (t != null) {
            return t;
        }
        if (name.startsWith("java.") || name.startsWith("javax.")) {
            return name; // 源码显式限定写法（java.util.List x / @javax.validation.Valid p）
        }
        if (JAVA_LANG.contains(name)) {
            return "java.lang." + name;
        }
        if (!ctx.pkg.isEmpty() && classesByFqn.containsKey(ctx.pkg + "." + name)) {
            return ctx.pkg + "." + name; // 同包（A1 全量注册后精确确认，与文件处理顺序无关）
        }
        for (String w : ctx.wildcardImports) {
            String cand = w + "." + name;
            if (classesByFqn.containsKey(cand)) {
                return cand;
            }
            String jdkName = JDK_COMMON_TYPES.get(name);
            if (jdkName != null && jdkName.startsWith(w + ".")) {
                return jdkName; // JDK wildcard（import java.util.* 等）：编译期 asType() 给全名
            }
            String guavaName = GUAVA_COLLECT_TYPES.get(name);
            if (guavaName != null && guavaName.startsWith(w + ".")) {
                return guavaName; // guava wildcard（import com.google.common.collect.*）：本文件 import 消歧
            }
        }
        Set<String> gi = globalImports.get(name);
        if (gi != null && gi.size() == 1) {
            return gi.iterator().next(); // 全仓唯一显式 import 记录（覆盖外部 wildcard import 场景）
        }
        List<ClassInfo> byName = classesBySimpleName.get(name);
        if (byName != null && byName.size() == 1) {
            return byName.get(0).fqn;
        }
        return null; // 不可信猜测一律不返回（如 Lombok 生成的 log 字段），交给全局唯一仲裁
    }

    /** ClassOrInterfaceType → 点分 FQN（不带泛型；解析失败保留源码写法）。 */
    private String resolveTypeName(ClassOrInterfaceType t, FileCtx ctx) {
        StringBuilder sb = new StringBuilder();
        ClassOrInterfaceType cur = t;
        while (cur.getScope().isPresent()) {
            sb.insert(0, "." + cur.getNameAsString()); // 点在段前（旧写法 name+"." 多段链拼成 OuterInner.）
            cur = cur.getScope().get();
        }
        sb.insert(0, cur.getNameAsString());
        String dotted = sb.toString();
        String resolved = resolveDottedPrefix(dotted, ctx);
        return resolved != null ? resolved : dotted;
    }

    /** sym 参数用类型渲染：FQN + 泛型实参（FQN）+ 数组 []（对齐编译期 TypeMirror.toString）。 */
    private String typeForSym(Type type, FileCtx ctx) {
        if (type instanceof PrimitiveType) {
            return type.asPrimitiveType().getType().asString();
        }
        if (type instanceof ArrayType) {
            return typeForSym(((ArrayType) type).getComponentType(), ctx) + "[]";
        }
        if (type instanceof WildcardType) {
            WildcardType w = (WildcardType) type;
            if (w.getExtendedType().isPresent()) {
                return "? extends " + typeForSym(w.getExtendedType().get(), ctx);
            }
            if (w.getSuperType().isPresent()) {
                return "? super " + typeForSym(w.getSuperType().get(), ctx);
            }
            return "?";
        }
        if (type instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType c = (ClassOrInterfaceType) type;
            String fqn = resolveTypeName(c, ctx);
            if (c.getTypeArguments().isPresent()) {
                StringBuilder args = new StringBuilder("<");
                List<Type> ts = c.getTypeArguments().get();
                for (int i = 0; i < ts.size(); i++) {
                    if (i > 0) {
                        args.append(',');
                    }
                    args.append(typeForSym(ts.get(i), ctx));
                }
                args.append('>');
                return fqn + args;
            }
            return fqn;
        }
        return type.toString();
    }

    /** 返回类型模板代入接收者泛型实参（模板占位符 E/T/K/V 按类型参数位置映射；
     *  残留未映射占位符（Stream#map 的 R）→ null 断链，宁缺勿错）。 */
    private String substituteTypeParams(String tmpl, String recvFull) {
        String raw = stripGenerics(recvFull);
        String[] names = JDK_TYPE_PARAMS.get(raw);
        String[] args = typeArgsOf(recvFull);
        String out = tmpl;
        if (names != null && args != null && names.length == args.length) {
            for (int i = 0; i < names.length; i++) {
                out = replaceWord(out, names[i], args[i]);
            }
        }
        return hasBarePlaceholder(out) ? null : out;
    }

    /** 调用实参静态类型数组（外部依赖签名渲染/仓内重载消歧用；不可知元素 null；无参为空数组）。 */
    private String[] argTypesOf(MethodCallExpr mce, FileCtx ctx, ClassInfo currentClass, VarScope scope) {
        return argTypesOf(mce.getArguments(), ctx, currentClass, scope);
    }

    private String[] argTypesOf(List<Expression> args, FileCtx ctx, ClassInfo currentClass, VarScope scope) {
        String[] ts = new String[args.size()];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = argTypeOf(args.get(i), ctx, currentClass, scope);
        }
        return ts;
    }

    /** 实参表达式静态类型（String 字面量 / 变量字段 / 链式构造 cast；数字字面量重载装箱不定等返回 null）。 */
    private String argTypeOf(Expression e, FileCtx ctx, ClassInfo currentClass, VarScope scope) {
        if (e instanceof StringLiteralExpr) {
            return "java.lang.String";
        }
        if (e instanceof NameExpr && scope != null) {
            return scope.vars.get(((NameExpr) e).getNameAsString());
        }
        if (e instanceof FieldAccessExpr || e instanceof MethodCallExpr
                || e instanceof ObjectCreationExpr || e instanceof CastExpr || e instanceof EnclosedExpr) {
            return resolveTypeOf(e, ctx, currentClass, scope);
        }
        return null;
    }

    /** 剥离顶层泛型实参（java.util.List<X> → java.util.List；嵌套 <> 深度匹配；无泛型原样返回）。 */
    private static String stripGenerics(String t) {
        int lt = t.indexOf('<');
        if (lt < 0) {
            return t;
        }
        int depth = 0;
        for (int i = lt; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0) {
                    return t.substring(0, lt) + t.substring(i + 1);
                }
            }
        }
        return t;
    }

    /** 顶层泛型实参（java.util.Map<K,V> → [K, V]；嵌套 <> 与 ? extends 正确切分；非泛型 null）。 */
    private static String[] typeArgsOf(String full) {
        int lt = full.indexOf('<');
        if (lt < 0 || !full.endsWith(">")) {
            return null;
        }
        List<String> parts = new ArrayList<String>();
        int depth = 1; // 顶层 '<' 已消耗，循环体内处于第一层
        int start = lt + 1;
        for (int i = lt + 1; i < full.length(); i++) {
            char c = full.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0 && i == full.length() - 1) {
                    parts.add(full.substring(start, i));
                    return parts.toArray(new String[0]);
                }
                if (depth <= 0) {
                    return null; // 顶层 '>' 不在末尾（畸形形态）
                }
            } else if (c == ',' && depth == 1) {
                parts.add(full.substring(start, i));
                start = i + 1;
            }
        }
        return null;
    }

    /** 标识符边界替换（占位符单字母防误伤：TreeMap 的 T 不会被当作占位符）。 */
    private static String replaceWord(String s, String word, String replacement) {
        if (s == null || s.length() < word.length()) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int j = s.indexOf(word, i);
            if (j < 0) {
                sb.append(s.substring(i));
                break;
            }
            int end = j + word.length();
            boolean boundary = (j == 0 || !isIdentChar(s.charAt(j - 1)))
                    && (end >= s.length() || !isIdentChar(s.charAt(end)));
            if (boundary) {
                sb.append(s, i, j).append(replacement);
                i = end;
            } else {
                sb.append(s, i, j + 1);
                i = j + 1;
            }
        }
        return sb.toString();
    }

    /** 残留裸占位符检测（标识符边界的单大写字母，如 Stream#map 的 R）——存在则无法实例化。 */
    private static boolean hasBarePlaceholder(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z'
                    && (i == 0 || !isIdentChar(s.charAt(i - 1)))
                    && (i + 1 >= s.length() || !isIdentChar(s.charAt(i + 1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private void registerSimpleName(String simpleName, ClassInfo ci) {
        List<ClassInfo> l = classesBySimpleName.get(simpleName);
        if (l == null) {
            l = new ArrayList<ClassInfo>();
            classesBySimpleName.put(simpleName, l);
        }
        l.add(ci);
    }

    private void registerGlobalMethod(String name, String sym, int params) {
        List<MethodSig> l = globalMethodsByName.get(name);
        if (l == null) {
            l = new ArrayList<MethodSig>();
            globalMethodsByName.put(name, l);
        }
        l.add(new MethodSig(sym, params));
    }

    private static int lineOf(SimpleName n) {
        return n.getRange().isPresent() ? n.getRange().get().begin.line : 0;
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getRange().isPresent() ? n.getRange().get().begin.line : 0;
    }

    /** jsonl 字符串值最小转义（与编译期处理器 esc 同语义）。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                if (sb == null) {
                    sb = new StringBuilder(s.substring(0, i));
                }
                sb.append('\\').append(c);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static String stripAsterisk(String importName) {
        return importName.endsWith(".*") ? importName.substring(0, importName.length() - 2) : importName;
    }

    /**
     * 收集待索引 java 文件：优先 src/main/java（对齐 mvn compile 的 main 源集），
     * 全仓无此结构时退化为全部 java（排除 target/test/build/node_modules，兼容纯源码仓）。
     */
    static List<File> collectJavaFiles(File repoDir) {
        List<File> all = new ArrayList<File>();
        List<File> mainOnly = new ArrayList<File>();
        walk(repoDir, "", all, mainOnly, 0);
        return mainOnly.isEmpty() ? all : mainOnly;
    }

    private static void walk(File dir, String rel, List<File> all, List<File> mainOnly, int depth) {
        // 上限 24 层防符号链接环；真实项目 com/company/product/module/feature/... 嵌套普遍 14+ 层
        if (depth > 24) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            String name = f.getName();
            if (f.isDirectory()) {
                if (name.startsWith(".") || name.equals("target") || name.equals("build")
                        || name.equals("out") || name.equals("node_modules")) {
                    continue;
                }
                String childRel = rel.isEmpty() ? name : rel + "/" + name;
                walk(f, childRel, all, mainOnly, depth + 1);
            } else if (name.endsWith(".java")) {
                String childRel = rel.isEmpty() ? name : rel + "/" + name;
                if (childRel.contains("/src/main/java/") || childRel.startsWith("src/main/java/")) {
                    mainOnly.add(f);
                }
                if (!childRel.contains("/src/test/") && !childRel.startsWith("src/test/")) {
                    all.add(f);
                }
            }
        }
    }

    private static String relativePath(File repoDir, File f) {
        String rp = f.getAbsolutePath().substring(repoDir.getAbsolutePath().length());
        if (rp.startsWith("/")) {
            rp = rp.substring(1);
        }
        return rp;
    }

    /**
     * CLI：独立生成静态索引（运维排查 / 精度对比用）。
     * <pre>java -cp ... StaticJavaIndexer &lt;repoDir&gt; &lt;out.jsonl&gt;</pre>
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: StaticJavaIndexer <repoDir> <out.jsonl>");
            System.exit(2);
        }
        Result r = new StaticJavaIndexer().index(new File(args[0]), new File(args[1]));
        System.out.println(r.summary() + " elapsedMs=" + r.elapsedMs);
        System.exit(r.ok() ? 0 : 1);
    }
}
