# Keep stack traces readable in exported logs.
-keepattributes SourceFile,LineNumberTable

# --- Optional classes referenced by the MQTT client (HiveMQ over netty) ---
# Each group below is code that only runs when the named library is on the classpath. HiveMQ and
# netty probe for it (Class.forName or a guarded holder class) and fall back when it is absent, so
# the references are dead on Android and R8 only needs telling not to fail on them.

# Native epoll transport: HiveMQ's NettyEventLoopProvider uses it only if netty-transport-native-
# epoll is present, otherwise NIO. That artifact ships Linux .so files, not Android ones.
-dontwarn io.netty.channel.epoll.**
# MQTT over WebSocket (netty-codec-http) and via HTTP/SOCKS proxies (netty-handler-proxy): HiveMQ
# declares both as optional dependencies, and the MQTT sink connects over plain TCP or TLS only.
-dontwarn io.netty.handler.codec.http.**
-dontwarn io.netty.handler.proxy.**
# netty-tcnative (OpenSSL via JNI): netty uses it only when it loads; TLS goes through the
# platform's JDK SSLEngine otherwise.
-dontwarn io.netty.internal.tcnative.**
# netty's InternalLoggerFactory tries SLF4J, then Log4j 2, then Log4j 1, then java.util.logging,
# by class presence. Only SLF4J's API is on the classpath.
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
# Jetty ALPN/NPN boot-classpath agents: only for Java 8 JVMs without built-in ALPN.
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
# BlockHound (a test-time blocking-call detector) integration, registered as a service that
# nothing on Android loads.
-dontwarn reactor.blockhound.integration.**

# --- Reflection R8 cannot see ---
# netty's shaded JCTools queues (the Unsafe-backed variants, picked when netty finds a usable
# sun.misc.Unsafe) read and write their index fields only through Unsafe offsets looked up by
# field name in a static initializer. R8 sees no direct access, deletes the fields, and the
# lookup then throws during class init. The AtomicFieldUpdater variants are already handled.
-keepclassmembers class io.netty.util.internal.shaded.org.jctools.** { <fields>; }
# sshj registers the bundled Bouncy Castle provider and asks it for algorithms by provider name.
# The provider instantiates its algorithm tables and implementation classes from class-name
# strings, so without these rules R8 strips them and the release build would run sshj against an
# empty provider instead of the one the JVM tests exercise.
-keep class org.bouncycastle.jcajce.provider.** { <init>(...); }
-keep class org.bouncycastle.jce.provider.** { <init>(...); }
