package mil.army.usace.hec.dss.migrator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Child-first classloader that isolates hec-monolith and its JNI native lib
 * from the host classpath. Class/resource bytes come from in-memory
 * {@code byte[]} jar contents; natives resolve via {@link #findLibrary(String)}
 * to absolute paths in the cache's native directory.
 */
final class IsolatedClassLoader extends ClassLoader {

    private static final String[] PARENT_FIRST_PREFIXES = {
            "java.", "javax.", "sun.", "jdk.", "com.sun."
    };

    private final Map<String, byte[]> classBytes;
    private final Map<String, List<byte[]>> resources;
    private final Path nativeLibDir;

    private IsolatedClassLoader(Map<String, byte[]> classBytes,
                                 Map<String, List<byte[]>> resources,
                                 ClassLoader parent,
                                 Path nativeLibDir) {
        super(parent);
        this.classBytes = classBytes;
        this.resources = resources;
        this.nativeLibDir = nativeLibDir;
    }

    static IsolatedClassLoader fromJarBytes(List<byte[]> jarContents,
                                             ClassLoader parent,
                                             Path nativeLibDir) throws IOException {
        Map<String, byte[]> classBytes = new HashMap<>();
        Map<String, List<byte[]>> resources = new HashMap<>();
        for (byte[] jarBytes : jarContents) {
            try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
                indexJarStream(jis, classBytes, resources);
            }
        }
        return new IsolatedClassLoader(classBytes, resources, parent, nativeLibDir);
    }

    private static void indexJarStream(JarInputStream jis,
                                        Map<String, byte[]> classBytes,
                                        Map<String, List<byte[]>> resources) throws IOException {
        JarEntry entry;
        while ((entry = jis.getNextJarEntry()) != null) {
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            byte[] bytes = jis.readAllBytes();
            if (name.endsWith(".class")) {
                classBytes.put(name.substring(0, name.length() - 6).replace('/', '.'), bytes);
            } else {
                resources.computeIfAbsent(name, k -> new ArrayList<>()).add(bytes);
            }
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                if (resolve) resolveClass(c);
                return c;
            }
            if (isParentFirst(name)) {
                return super.loadClass(name, resolve);
            }
            try {
                c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classBytes.get(name);
        if (bytes == null) throw new ClassNotFoundException(name);
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        if (url != null) return url;
        return getParent() != null ? getParent().getResource(name) : null;
    }

    @Override
    protected URL findResource(String name) {
        List<byte[]> list = resources.get(name);
        return (list == null || list.isEmpty()) ? null : toMemoryUrl(name, list.get(0));
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> result = new ArrayList<>();
        List<byte[]> list = resources.get(name);
        if (list != null) {
            for (byte[] bytes : list) result.add(toMemoryUrl(name, bytes));
        }
        if (getParent() != null) {
            Enumeration<URL> parentResources = getParent().getResources(name);
            while (parentResources.hasMoreElements()) result.add(parentResources.nextElement());
        }
        return Collections.enumeration(result);
    }

    /** Resolves native library names to absolute paths in the extracted native directory. */
    @Override
    protected String findLibrary(String libname) {
        String mappedName = System.mapLibraryName(libname);
        Path libPath = nativeLibDir.resolve(mappedName);
        if (Files.exists(libPath)) {
            return libPath.toAbsolutePath().toString();
        }
        // macOS: mapLibraryName returns .dylib but some JNI libs use .jnilib.
        if (mappedName.endsWith(".dylib")) {
            Path jnilibPath = nativeLibDir.resolve(mappedName.replace(".dylib", ".jnilib"));
            if (Files.exists(jnilibPath)) {
                return jnilibPath.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static URL toMemoryUrl(String resourceName, byte[] bytes) {
        try {
            URLStreamHandler handler = new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL u) {
                    return new URLConnection(u) {
                        @Override public void connect() {}
                        @Override public InputStream getInputStream() {
                            return new ByteArrayInputStream(bytes);
                        }
                    };
                }
            };
            return new URL("memory", "", -1, resourceName, handler);
        } catch (Exception e) {
            return null;
        }
    }
}
