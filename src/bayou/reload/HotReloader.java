package bayou.reload;

import _bayou._log._Logger;
import _bayou._tmp._FileMonitor;
import _bayou._tmp._PrefixedMsgOut;
import _bayou._tmp._Util;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * For reloading app instances when source files are changed.
 * <p>
 *     A "shell" creates a HotReloader, and obtains app instances by calling
 *     {@link #getAppInstance(String) HotReloader.getAppInstance(appClassName)},
 *     often to handle a task, e.g. to process an incoming http request.
 *     The <code>getAppInstance()</code> method returns the same instance
 *     (for the same class name) until reloading occurs
 *     -- if some source files are changed, <code>getAppInstance()</code> may trigger
 *     recompilation and reloading, returning a new app instance created in a new app class loader.
 * </p>
 * <p>
 *     HotReloader is thread-safe, particularly, <code>getAppInstance()</code> can be invoked concurrently.
 * </p>
 * <h4 id=compilers>Compilers</h4>
 * <p>
 *     The HotReloader maintains a list of {@link bayou.reload.HotCompiler}s, added by the shell through
 *     {@link #addCompiler(HotCompiler, boolean, java.nio.file.Path...) addCompiler()}.
 *     Each compiler is responsible for a certain kind of source files under the source dirs.
 * </p>
 * <p>
 *     If compiler C2 depends on C1 (e.g. C2 consumes the output files of C1),
 *     C2 should be added after C1.
 * </p>
 * <p>
 *     When <code>getAppInstance()</code> is invoked, if some source files are changed,
 *     the corresponding compilers will be invoked to recompile them.
 *     Recompilation may or may not trigger reloading.
 * </p>
 * <h4 id=class-loaders>Class Loaders</h4>
 * <p>
 *     The shell class loader (the one that loads the class of this HotReloader instance)
 *     is required to be a {@link java.net.URLClassLoader}.
 *     The app class loader is a sibling of the shell class loader, sharing the same parent.
 * </p>
 * <p>
 *     The app class loader is also a URLClassLoader, initially containing the same class paths
 *     as the shell's (usually the JVM's class paths). Additional class paths can be added
 *     to the app class loader by {@link #prependClassPath(java.nio.file.Path)}.
 * </p>
 * <h4 id=shared-classes>Shared Classes</h4>
 * <p>
 *     The shell and the app share the same classes loaded by the parent class loader,
 *     notably all the JRE classes.
 * </p>
 * <p>
 *     In addition, the app needs to share some classes that's loaded by the shell.
 *     Often, the shell interacts with the app instance through an interface, e.g.
 *     {@link bayou.http.HttpHandler}, it's necessary that the app sees the same <code>Class</code>
 *     for that interface, and all interfaces/classes referenced by that interface, recursively.
 *     The shell also needs to share other classes to communicate with the app,
 *     e.g. {@link bayou.async.FiberLocal}.
 *     The shell can specify these classes by {@link #addSharedClasses(Class[])}.
 * </p>
 * <h4 id=unloading>Unloading</h4>
 * <p>
 *     When reloading occurs, the previous app class loader and app instances are dereferenced from this HotReloader.
 *     Hopefully they will be garbage collected soon.
 * </p>
 * <p>
 *     If an app instance implements {@link java.lang.AutoCloseable},
 *     the <code>close()</code> method will be called upon unloading.
 * </p>
 * <p>
 *     An unloaded app instance may still be functioning for some time,
 *     e.g. to continue processing previously accepted http requests.
 * </p>
 * <p>
 *     Threads created by an app instance may persist after unloading; this is very bad,
 *     especially because it likely references the app class loader, hindering garbage collection.
 *     To avoid that, use thread executors with timeouts (even for core threads);
 *     or do necessary cleanup/shutdown actions in the <code>close()</code> method mentioned above.
 * </p>
 */

    // if AsynchronousFileChannel is used, there are some bugs in java.nio that erroneously reference
    // user class loaders, preventing them from being garbage collected. fortunately it happens only
    // on the 1st use, so at most one garbage OurClassLoader is retained in memory, which is not too bad.

public class HotReloader
{
    final Object lock(){ return this; }

    final ClassLoaderFactory classLoaderFactory;
    final Builder builder;

    final HashMap<String,Object> instanceMap;  // class name to instance
    ClassLoader currClassLoader;

    volatile Consumer<CharSequence> msgOut = new _PrefixedMsgOut(System.out, "[hot]   ");

    /**
     * Create a HotReloader instance.
     * <p>
     *     The instance is not very useful yet; call {@link #addCompiler addCompiler()}
     *     and {@link #addSharedClasses addSharedClasses()} etc to set it up.
     * </p>
     */
    public HotReloader()
    {
        ClassLoader launcherCL = this.getClass().getClassLoader(); // likely a sun.misc.Launcher$AppClassLoader
        if(!(launcherCL instanceof URLClassLoader))
            throw new AssertionError("The class loader of HotReloader.class is not a URLClassLoader: "+launcherCL);
        // in Sun JVM, usually the class loader parent-child chain is
        //     bootstrap class loader - for jre/lib     classes
        //     extension class loader - for jre/lib/ext classes
        //     system    class loader - for class path  classes
        // usually launcherCL is the system class loader. appCL shares parent with launcherCL,
        // therefore all bootstrap/extension classes are shared.

        this.classLoaderFactory = new ClassLoaderFactory((URLClassLoader)launcherCL);

        this.builder = new Builder();

        this.instanceMap = new HashMap<>();

    }

    /**
     * Get the app instance for the class name.
     * <p>
     *     The same instance will be returned for the same class name,
     *     until reloading occurs,
     *     then a new instance will be created in a new class loader.
     * </p>
     * <p>
     *     The app class must have a public 0-arg constructor.
     * </p>
     */

    // we may want to support constructor args in future:
    //     getAppInstance("foo.Bar", "x", 1)  => new foo.Bar("x", 1)
    // useful if app need different conf vars (for production/testing etc)
    // workaround:
    //     BaseApp(args...)
    //     ProductionApp(){ super(productionArgs); }
    //     TestingApp(){ super(testingArgs); }

    public Object getAppInstance(String appClassName) throws Exception
    {
        synchronized (lock())
        {
            // rebuild every time
            // build() is very cheap if there's not file change since last getAppInstance()
            boolean reload;
            try
            {
                reload = builder.build();
            }
            catch (Exception e) // BuildException, or something else
            {
                unloadOldInstances();
                throw e;  // caller show error message
            }

            if(reload)
                unloadOldInstances();

            // we'll create a new class loader on reload, even if no classes are changed,
            // e.g. the reload is due to some .properties file changes
            // this is for max isolation of app instances.

            if(currClassLoader==null)
            {
                currClassLoader = classLoaderFactory.newClassLoader();
                msgOut.accept("new class loader: "+currClassLoader);
            }

            Object instance = instanceMap.get(appClassName);

            if(instance==null)
            {
                msgOut.accept("create a new instance in "+currClassLoader);
                // throws.
                Class clazz = Class.forName(appClassName, false, currClassLoader);
                msgOut.accept("    new " + appClassName + "()");
                instance = clazz.newInstance(); // exception propagation - see javadoc
                instanceMap.put(appClassName, instance);
                msgOut.accept("new instance created: " + instance);

                System.gc();
                long totalMemory = Runtime.getRuntime().totalMemory();
                long usedMemory  = totalMemory - Runtime.getRuntime().freeMemory();
                msgOut.accept(String.format("memory usage: %,dM of %,dM", usedMemory >> 20, totalMemory >> 20));
                msgOut.accept("");
            }

            return instance;
        }
    }



    /**
     * Add a <a href="#compilers">compiler</a>.
     * <p>
     *     If <code>reload=true</code>, reloading is needed after this compiler recompiles some source files.
     *     It is possible that a recompilation does not require reloading, because the app can handle the
     *     new outputs of the compiler on-the-fly, without being reloaded.
     * </p>
     * <p>
     *     See also convenience methods that add compilers:
     *     {@link #onJavaFiles onJavaFiles()}, {@link #onClassFiles onClassFiles()}, {@link #onFiles onFiles()}.
     * </p>
     */
    // output dir is decided by compiler
    public void addCompiler(HotCompiler compiler, boolean reload, Path... srcDirs)
    {
        synchronized (lock())
        {
            builder.addUnit(msgOut, compiler, reload, srcDirs);
            unloadOldInstances();
        }
    }


    /**
     * Add <a href="#shared-classes">shared classes</a>.
     * <p>
     *     Dependant classes referenced in the public/protected APIs of <code>sharedClasses</code>
     *     will be added as shared classes as well (recursively).
     * </p>
     */
    // if necessary, we could provide a version that doesn't expand recursively. that gives user more precise control.
    // at this point, we don't think it's needed; it seems unlikely that a dependent class does not need to be shared.
    public void addSharedClasses(Class<?>... sharedClasses)
    {
        synchronized (lock())
        {
            int n = classLoaderFactory.addSharedClasses(sharedClasses);
            if(n>0)
                unloadOldInstances();
            // if no new class is added, no need to unload old instances.
            // e.g. two HotHttpHandler sharing a reloader
        }
    }
    // addSharedPackages(...)?

    /**
     * Prepend a class path to the app class loader.
     * @param path refers to a class dir or a jar file
     */
    // accepts only one path here.
    // we force caller to prepend one path at a time, so that the order of class paths is obvious.
    public void prependClassPath(Path path) throws Exception
    {
        synchronized (lock())
        {
            classLoaderFactory.prependClassPath(path);
            unloadOldInstances();
        }
    }

    static Path[] toPaths(String... strings)
    {
        Path[] paths = new Path[strings.length];
        for(int i=0; i<strings.length; i++)
            paths[i] = Paths.get(strings[i]);
        return paths;
    }

    /**
     * Reload on java file changes.
     * <p>
     *     This method adds a {@link JavacCompiler}. If java files under the <code>srcDirs</code>
     *     are changed, they will be recompiled, and reloading will occur.
     * </p>
     * <p>
     *     The javac options are <code>"-g -parameters"</code>.
     * </p>
     * <p>
     *     The source directory structure must follow the package structure.
     *     Each java file must contain only one top-level class.
     *     For example, class <code>"foo.Bar"</code> must be defined in a "foo/Bar.java" file
     *     under one of the <code>srcDirs</code>.
     * </p>
     * <p>
     *     The output dir for class files is determined by
     *     {@link JavacCompiler#tmpOutDirFor JavacCompiler.tmpOutDirFor(srcDirs)}.
     *     The directory will be cleaned before the first compilation.
     * </p>
     * @return this
     */
    // add a javac compiler, prepend javac out dir to classpath
    // this works for all IDEs, and those who don't use any IDE.
    // to clean-build in the beginning, try delete out dir first
    public HotReloader onJavaFiles(String... srcDirs) throws Exception
    {
        if(srcDirs==null || srcDirs.length==0)
            throw new IllegalArgumentException("javaSrcDirs is empty");
        Path[] srcDirsP = toPaths(srcDirs);

        for(Path dir : srcDirsP)
            if(!Files.isDirectory(dir))
                throw new IOException("Not a dir: "+dir.toAbsolutePath());

        // classes under diff dirs may depend on each other; compile them together, with one javac

        Path outDir = JavacCompiler.tmpOutDirFor(srcDirs);
        _Util.deleteDir(outDir); // do a clean build on startup. in future, may provide an option not to.
        Files.createDirectories(outDir);  // javac won't create it

        List<Path> extraClassPaths = Collections.emptyList(); // none

        List<String> javacOptions = Arrays.asList("-g", "-parameters");
        JavacCompiler javac = new JavacCompiler(Arrays.asList(srcDirsP), outDir, extraClassPaths, javacOptions);

        prependClassPath(outDir);
        addCompiler(javac, true, srcDirsP);

        msgOut.accept("java source dir");
        for(Path dir : srcDirsP)
            msgOut.accept("    " + dir.toAbsolutePath());
        msgOut.accept("javac out dir");
        msgOut.accept("    " + outDir);
        msgOut.accept("");

        return this;
    }

    /**
     * Reload on class file changes.
     * <p>
     *     If class files under the JVM classpath are changed, reloading will occur.
     * </p>
     * @return this
     */
    // this is useful e.g. for eclipse users. eclipse will auto-compile on save.
    // can also be used in IntelliJ - Ctrl+F9 to build
    // good: IDE compile probably smarter, e.g. recompile if a constant changes.
    // bad:  compile error not displayed in browser. but compile error is rare for IDE users.
    // note that jar files in classpath are not monitored. we are being lazy here.
    //     it's a little work to find the common dirs of jars for FileMonitor.
    //     we imagine during development, only classes are being updated, not jars.
    //     if necessary, users can use onFiles() to reload on jar change
    public HotReloader onClassFiles()
    {
        Path[] classDirs = findDirsInClasspath();
        addCompiler(VoidCompiler.of("class", "glob:**.class"), true, classDirs);

        msgOut.accept("class dir");
        for(Path dir : classDirs)
            msgOut.accept("    " + dir.toAbsolutePath());

        return this;
    }

    static Path[] findDirsInClasspath()
    {
        String jcp = System.getProperty("java.class.path");
        if(jcp==null || jcp.isEmpty())
            throw new AssertionError("system property 'java.class.path' undefined");

        ArrayList<Path> results = new ArrayList<>();
        String[] ss = jcp.split("\\" + File.pathSeparatorChar);
        for(String s : ss)
        {
            Path path = Paths.get(s).toAbsolutePath().normalize();
            if(Files.isDirectory(path))
                results.add(path);
        }
        if(results.isEmpty())
            throw new IllegalStateException("no dir found in classpath: "+jcp);
        return results.toArray(new Path[results.size()]);
    }

    /**
     * Reload on file changes.
     * <p>
     *     If files under <code>srcDirs</code> matching the <code>filePattern</code> are changed,
     *     reloading will occur.
     * </p>
     * <p>
     *     The format of <code>filePattern</code> is specified in
     *     {@link java.nio.file.FileSystem#getPathMatcher(String)}.
     * </p>
     * <p>
     *     Example use case: If the app reads <code>".properties"</code> files
     *     on startup and cache the information in memory,
     *     it needs to be reloaded if some of the files are changed.
     * </p>
     * <pre>
     *     hotReloader.onFiles("prop file", "glob:**.properties", SRC_DIR)
     * </pre>
     * @param fileDesc
     *        description of the file type, used for diagnosis outputs
     * @return this
     * @see bayou.reload.VoidCompiler
     */
    public HotReloader onFiles(String fileDesc, String filePattern, String... srcDirs)
    {
        addCompiler(VoidCompiler.of(fileDesc, filePattern), true, toPaths(srcDirs));

        msgOut.accept(fileDesc + " dir");
        for(String dir : srcDirs)
            msgOut.accept("    " + Paths.get(dir).toAbsolutePath());

        return this;
    }









    /**
     * Where diagnosis messages will be printed to.
     * <p>
     *     By default the messages will be printed to <code>System.out</code>.
     *     Call {@link #setMessageOut(Consumer)} to change that.
     * </p>
     */
    public Consumer<CharSequence> getMessageOut()
    {
        return msgOut;
    }

    /**
     * Set where diagnosis messages will be printed to.
     * <p>
     *     Examples:
     * </p>
     * <pre>
     *     setMessageOut(System.err::println);
     *
     *     setMessageOut( msg-&gt;{} ); // silence!
     * </pre>
     */
    public void setMessageOut(Consumer<CharSequence> msgOut)
    {
        _Util.require(msgOut!=null, "msgOut!=null");
        this.msgOut = msgOut; // volatile!
    }












    void unloadOldInstances()
    {
        for(Object instance : instanceMap.values())
        {
            msgOut.accept("unload instance: " + instance);
            if(instance instanceof AutoCloseable)
            {
                msgOut.accept("    invoke close() method");
                try
                {
                    ((AutoCloseable)instance).close();
                }
                catch (Exception e)
                {
                    _Logger.of(HotReloader.class).error("%s", e);
                }
            }
        }
        instanceMap.clear();
        currClassLoader = null;
    }






    static class Builder
    {
        ArrayList<BuildUnit> units = new ArrayList<>();

        public void addUnit(Consumer<CharSequence> msgOut, HotCompiler compiler, boolean reloadAfterCompile, Path... srcDirs)
        {
            _PrefixedMsgOut fmMsgOut = new _PrefixedMsgOut(msgOut, "FileMonitor Warning: ");
            _FileMonitor fileMonitor = new _FileMonitor(fmMsgOut, compiler.getPathMatcher(), srcDirs);
            BuildUnit unit = new BuildUnit(msgOut, fileMonitor, compiler, reloadAfterCompile);
            units.add(unit);
        }

        // return true if app needs reload
        public boolean build() throws Exception
        {
            boolean reload = false;
            for(BuildUnit unit : units)
            {
                boolean somethingCompiled = unit.compile(); // throws
                if(somethingCompiled && unit.reloadAfterCompile)
                    reload = true;
            }
            return reload;
        }
    }

    static class BuildUnit
    {
        final Consumer<CharSequence> msgOut;
        final _FileMonitor fileMonitor;
        final HotCompiler compiler;
        final boolean reloadAfterCompile;

        boolean sync;  // whether sources and output are in sync, i.e. last compile succeeds. started as false

        BuildUnit(Consumer<CharSequence> msgOut, _FileMonitor fileMonitor,
                  HotCompiler compiler, boolean reloadAfterCompile)
        {
            this.msgOut = msgOut;
            this.fileMonitor = fileMonitor;
            this.compiler = compiler;
            this.reloadAfterCompile = reloadAfterCompile;
        }

        boolean compile() throws Exception
        {
            boolean somethingCompiled = true;
            boolean prevSync = sync;
            sync = false; // if anything goes wrong

            if(prevSync) // we only need to compile changed files
            {
                List<Set<Path>> changes = fileMonitor.pollFileChanges();
                Set<Path> created=changes.get(0), updated=changes.get(1), deleted=changes.get(2);
                if( created.isEmpty() && updated.isEmpty() && deleted.isEmpty() )
                    somethingCompiled = false; // fast path: prev sync, no file changes since -> no action
                else
                    compiler.compile(msgOut, created, updated, deleted);
            }
            else // out of sync, rebuild all files
            {
                Set<Path> allFiles = fileMonitor.getAllFiles();
                // allFiles may be empty (not usual), but still counted as somethingCompiled=true.
                compiler.compile(msgOut, allFiles);
            }

            sync = true; // nothing went wrong
            return somethingCompiled;
        }
    }






    static class ClassLoaderFactory
    {
        final ClassLoader parentCL;
        final ArrayDeque<URL> allUrls = new ArrayDeque<>();

        final ArrayDeque<URL> ourUrls = new ArrayDeque<>();
        final HashMap<String,Class<?>> sharedClasses = new HashMap<>();

        ClassLoaderFactory(URLClassLoader launcherClassLoader)
        {
            this.parentCL = launcherClassLoader.getParent(); // could be null.

            this.allUrls.addAll(Arrays.asList(launcherClassLoader.getURLs()));
        }

        void prependClassPath(Path path) throws Exception  // dir or jar
        {
            URL url = path.toAbsolutePath().normalize().toUri().toURL(); // throws
            // e.g.
            // file:/work/out_dir/   (dir, notice the trailing slash)
            // file:/work/xxx.jar    (jar, URLClassLoader accepts this form)
            ourUrls.addFirst(url);
            allUrls.addFirst(url);
        }

        // return number of classes actually added
        int addSharedClasses(Class<?>... classes)
        {
            int size0 = sharedClasses.size();
            new VisitClasses(parentCL, sharedClasses).add(classes);
            return sharedClasses.size() - size0;
        }

        ClassLoader newClassLoader() throws Exception
        {
            URL[] allUrlsClone = allUrls.toArray(new URL[allUrls.size()]);
            URL[] ourUrlsClone = ourUrls.toArray(new URL[ourUrls.size()]);
            HashMap<String,Class<?>> sharedClassesClone = new HashMap<>(sharedClasses);

            return new OurClassLoader(allUrlsClone, parentCL, ourUrlsClone, sharedClassesClone);
        }
    }

    static class OurClassLoader extends URLClassLoader
    {
        static final AtomicInteger idSeq = new AtomicInteger(0);

        final int id = idSeq.incrementAndGet();
        final URL[] ourUrls;
        final HashMap<String,Class<?>> sharedClasses;
        OurClassLoader(URL[] allUrls, ClassLoader parent, URL[] ourUrls, HashMap<String, Class<?>> sharedClasses)
        {
            super(allUrls, parent);

            this.ourUrls = ourUrls;
            this.sharedClasses = sharedClasses;
        }

        @Override
        public String toString()
        {
            return "classLoader#" + id;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
        {
            Class<?> sharedClass = sharedClasses.get(name); // concurrent access is ok
            if(sharedClass!=null)
                return sharedClass;  // don't care about `resolve`. not important

            return super.loadClass(name, resolve);
        }

        // super.getResources() calls parent/bootstrap getResources() then this.findResources()

        @Override
        public Enumeration<URL> findResources(String name) throws IOException
        {
            // our urls shadows sys urls. we own exclusively all names under our urls.
            // if a resource is found both under our url and sys url, we must hide the result from sys url
            ArrayList<URL> ourResults = new ArrayList<>();
            ArrayList<URL> sysResults = new ArrayList<>();
            Enumeration<URL> allResults = super.findResources(name);
            while(allResults.hasMoreElements())
            {
                URL url = allResults.nextElement();
                // e.g.  (name=org/foo)
                //     file:/work/out__dir/org/foo
                // jar:file:/work/xxx.jar!/org/foo

                (isOurs(url) ? ourResults : sysResults)
                        .add(url);
            }

            // if there is any our result, discard all sys result
            return Collections.enumeration( ourResults.isEmpty()? sysResults : ourResults);
        }


        // our url (dir/jar) vs resource url
        //  [1]                               [2]
        //  file:/work/out__dir/                  file:/work/xxx.jar
        //  file:/work/out__dir/org/foo       jar:file:/work/xxx.jar!/org/foo
        //
        //
        boolean isOurs(URL rscUrl)
        {
            String urlStr = rscUrl.toExternalForm();
            for(URL ourUrl : ourUrls)
            {
                String ourStr = ourUrl.toExternalForm();
                if(urlStr.startsWith(ourStr))  // [1]
                    return true;
                if(urlStr.startsWith("jar:"+ourStr+"!/"))   // [2]
                    return true;
            }
            return false;
        }
    }



}
