package bayou.reload;

import _bayou._tmp._CryptoUtil;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

/**
 * A HotCompiler for java files.
 * <p>
 *     This compiler compiles <code>".java"</code> files under the <code>srcDirs</code>
 *     to <code>".class"</code> files under the <code>outDir</code>.
 * </p>
 * <p>
 *     See also {@link HotReloader#onJavaFiles(String...)}.
 * </p>
 * <p>
 *     The source directory structure must follow the package structure.
 *     Each java file must contain only one top-level class.
 *     For example, class <code>"foo.Bar"</code> must be defined in a "foo/Bar.java" file
 *     under one of the <code>srcDirs</code>.
 * </p>
 * <p>
 *     This compiler compares timestamps of source files and output files,
 *     skips java files that are older than the corresponding class files.
 *     This can be problematic. If necessary, delete the <code>outDir</code> and restart JVM.
 * </p>
 * <p>
 *     This implementation depends on {@link javax.tools.ToolProvider#getSystemJavaCompiler()}.
 * </p>
 */
public class JavacCompiler implements HotCompiler
{
    // problem[1]
    // we assume java files are in dir structures same as their package structures,
    // and each java file contains only one top level class of the same name as the file.
    // if the assumption fails, we may fail to find class files it corresponds to.

    // problem[2]
    // if a java file is older than class file, it'll not be recompiled.
    // sometimes that's not right, and recompile is needed, e.g. constant expression.

    // we need srcDirs to determine the packages of .java files
    final Path[] srcDirs;
    final Path outDir;
    final List<File> extraClassPaths;
    final List<String> javacOptions;

    /**
     * Create a JavacCompiler.
     *
     * @param extraClassPaths
     *        extra class paths for javac; usually empty
     * @param javacOptions
     *        options for javac, for example, <code>["-g", "-parameters"]</code>
     */

    // extraClassPaths: prepended to default class paths (JVM class paths)
    //    example use case: 2 java modules M1&M2, M2 depends on M1.
    //        2 separate javac compilers; #1's out dir is #2's extra class path
    public JavacCompiler(List<Path> srcDirs, Path outDir, List<Path> extraClassPaths, List<String> javacOptions)
    {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if(javac==null)
            throw new AssertionError("javac not found: ToolProvider.getSystemJavaCompiler() returns null");
        // discard javac. we'll need a new one each time we compile.

        this.srcDirs = new Path[srcDirs.size()];
        for(int i=0; i<srcDirs.size(); i++)
            this.srcDirs[i] = srcDirs.get(i).toAbsolutePath().normalize();

        this.outDir = outDir.toAbsolutePath().normalize();

        this.extraClassPaths = new ArrayList<>();
        this.extraClassPaths.add(this.outDir.toFile());  // our out dir must be the first for type searching.
        for(Path path : extraClassPaths)
            this.extraClassPaths.add(path.toFile());

        this.javacOptions = new ArrayList<>(javacOptions);
    }

    // a javac out dir under /tmp; it's exact location is entirely determined by srcDirs

    /**
     * Create an <code>outDir</code> under <code>"/tmp"</code> for <code>srcDirs</code>.
     * <p>
     *     The exact location is uniquely determined by the <code>srcDirs</code>.
     *     An example dir: <code>"/tmp/bayou/hot_reloader_javac_out/67E5A7ED8DDE8CE4"</code>
     * </p>
     */
    static public Path tmpOutDirFor(String... srcDirs) throws Exception
    {
        String[] srcDirsX = new String[srcDirs.length];
        for(int i=0; i<srcDirs.length; i++)
            srcDirsX[i] = Paths.get(srcDirs[i]).toAbsolutePath().normalize().toString();

        String hash = _CryptoUtil.md5(null, 8, (Object[]) srcDirsX); // must avoid collision. 8 bytes probably enough.

        return Paths.get("/tmp", "bayou", "hot_reloader_javac_out", hash);
    }

    /**
     * The PathMatcher for source files.
     * <p>
     *     All <code>".java"</code> files are matched, except files like <code>"package-info.java"</code>.
     * </p>
     */
    @Override
    public PathMatcher getPathMatcher()
    {
        return path -> {
            String str = path.toString();
            if(!str.endsWith(".java"))
                return false;
            if(str.indexOf('-')!=-1)  // e.g. package-info.java
                return false;
            return true;
        };
    }

    @Override
    public void compile(Consumer<CharSequence> msgOut, Set<Path> allFiles) throws Exception
    {
        msgOut.accept("java files: " + allFiles.size() + " total");

        jc_sync(msgOut, srcDirs, allFiles, outDir, extraClassPaths, javacOptions);
        // problem[1] problem[2]
    }

    @Override
    public void compile(Consumer<CharSequence> msgOut,
                        Set<Path> createdFiles, Set<Path> updatedFiles, Set<Path> deletedFiles)
            throws Exception
    {
        if(createdFiles.size()>0)
            msgOut.accept("java files: " + createdFiles.size() + " created");
        if(updatedFiles.size()>0)
            msgOut.accept("java files: " + updatedFiles.size() + " updated");
        if(deletedFiles.size()>0)
            msgOut.accept("java files: " + deletedFiles.size() + " deleted");

        // the 2nd line is necessary, e.g. a nested class is removed from a java file
        jc_deleteClasses(srcDirs, deletedFiles, outDir);
        jc_deleteClasses(srcDirs, updatedFiles, outDir);
        // problem[1]

        ArrayList<Path> freshJavaFiles = new ArrayList<>();
        freshJavaFiles.addAll(createdFiles);
        freshJavaFiles.addAll(updatedFiles);

        jc_compile(msgOut, freshJavaFiles, outDir, extraClassPaths, javacOptions);
        // problem[2]
    }


    //======================================================================================================

    // sync .java files and .class files.
    // for every .java file, check the corresponding .class files.
    // if the .class files exist and are newer than the .java file, the .java file needs no recompilation,
    // otherwise the .class files are removed then the .java file is recompiled.
    // if a .class file has no corresponding .java file, the .class file will be deleted.
    // problem[1]: if a .java file contains other top level classes, they'll be deleted!
    //     (they could be regenerated if the .java file is recompiled)
    // usually this method is called at the beginning of app.
    // after that, monitor java file changes, call compile() and deleteClasses(), to keep java/class in sync.
    static void jc_sync(Consumer<CharSequence> msgOut, Path[] srcDirs, Set<Path> javaFiles, Path outDir,
                        List<File> extraClassPaths, List<String> options) throws Exception
    {
        ArrayList<Path> freshJavaFiles = new ArrayList<>();
        final HashSet<Path> freshClassFiles = new HashSet<>();

        for(Path javaFile : javaFiles)
        {
            javaFile = javaFile.toAbsolutePath().normalize();
            ArrayList<Path> classFiles = jc_getClassFiles(srcDirs, javaFile, outDir);
            // problem[1]. not harmful here. if class file not found, treat java file as new
            boolean sync;
            if(classFiles.isEmpty()) // new java file, no class files
                sync = false;
            else
                sync = classFilesUpToDate(javaFile, classFiles);

            if(sync)
                freshClassFiles.addAll(classFiles);
            else
                freshJavaFiles.add(javaFile);
            // if !sync, all corresponding .class files are non-fresh, will be deleted before recompile.
            // that's necessary since some nested classes may no longer exist in the updated java file
        }

        // delete all non-fresh .class files. they correspond to deleted or updated .java files
        // problem[1]: if A.java contains top level class B, B.class will be deleted
        //     if A.java is older than A.class, it'll not be recompiled, therefore B.class will be missing
        Files.walkFileTree(outDir, new SimpleFileVisitor<Path>()
        {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                if(file.getFileName().toString().endsWith(".class"))
                    if(!freshClassFiles.contains(file))
                        Files.delete(file);

                return FileVisitResult.CONTINUE;
            }
        }); // does not follow sym link

        jc_compile(msgOut, freshJavaFiles, outDir, extraClassPaths, options);
        // problem[2]
    }

    static boolean classFilesUpToDate(Path javaFile, List<Path> classFiles) throws IOException
    {
        assert classFiles.size()>0;
        long javaTime = Files.getLastModifiedTime(javaFile).toMillis();
        for(Path classFile : classFiles)
        {
            long classTime = Files.getLastModifiedTime(classFile).toMillis();
            if(classTime < javaTime)
                return false;
        }
        return true;
    }

    // compile given .java files, output .class files under outDir.
    // usually this method is called after some java files are created/modified.
    static void jc_compile(Consumer<CharSequence> msgOut, ArrayList<Path> javaFiles, Path outDir,
                           List<File> extraClassPaths, List<String> options) throws Exception
    {
        if(javaFiles.isEmpty())
            return; // JavaCompiler would throw IllegalStateException: no source files

        msgOut.accept("javac compiling " + javaFiles.size() + " files...");

        ArrayList<File> javaFilesF = new ArrayList<>();
        for(Path path : javaFiles)
            javaFilesF.add(path.toFile());
        File outDirF = outDir.toFile();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler(); // must be a new instance
        // it's not null. we tested in constructor.

        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(outDirF)); // must be one dir
        if(!extraClassPaths.isEmpty()) // prepend it to default(vm) classpath
        {
            ArrayList<File> classPaths = new ArrayList<>();
            classPaths.addAll(extraClassPaths);
            for(File classPath : fileManager.getLocation((StandardLocation.CLASS_PATH)))
                classPaths.add(classPath);
            fileManager.setLocation(StandardLocation.CLASS_PATH, classPaths);
        }

        StringWriter sw = new StringWriter();  // for javac messages

        Iterable<? extends JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjectsFromFiles(javaFilesF);
        JavaCompiler.CompilationTask task = compiler.getTask(sw, fileManager, null, options, null, compilationUnits);
        Boolean ok = task.call();
        String javacMsg = sw.toString();

        fileManager.close();

        if(ok.booleanValue())
        {
            if(!javacMsg.isEmpty())  // warning, info etc
                msgOut.accept(System.lineSeparator() + javacMsg);
            msgOut.accept("javac passes.");
        }
        else
        {
            msgOut.accept("javac fails.");
            throw new Exception("javac fails"+NL+NL+HR+NL+NL+javacMsg+NL+HR+NL);

        }
    }
    static final String NL = System.lineSeparator();
    static final String HR = "------------------------------------------------------------------------------";

    // delete .class files that correspond to the given javaFiles. // problem[1]
    // usually this method is called after some java files are updated or deleted,
    // and we need to delete corresponding classes files.
    static void jc_deleteClasses(Path[] srcDirs, Set<Path> javaFiles, Path outDir) throws IOException
    {
        for(Path javaFile : javaFiles)
        {
            javaFile = javaFile.toAbsolutePath().normalize();
            ArrayList<Path> classFiles = jc_getClassFiles(srcDirs, javaFile, outDir);
            for(Path classFile : classFiles)
                Files.delete(classFile); // throws
        }
    }

    // get corresponding class files for the .java file. based on java file path/name. problem[1]
    // all paths are absolute and normalized
    static ArrayList<Path> jc_getClassFiles(Path[] srcDirs, Path javaFile, Path outDir) throws IOException
    {
        Path srcDir=null;
        for(Path sd : srcDirs)
        {
            if(javaFile.startsWith(sd))
            {
                srcDir = sd;
                break;
            }
        }
        if(srcDir==null)
            throw new AssertionError("the java file is not under srcDirs: "+javaFile);

        // outDir:    /yyy/out
        // srcDir:    /xxx/src
        // javaFile:  /xxx/src/foo/bar/Xyz.java

        javaFile = srcDir.relativize(javaFile); // foo/bar/Xyz.java
        Path pp = javaFile.getParent();  // foo/bar  // can be null, if in default package
        outDir = (pp==null)? outDir : outDir.resolve(pp);  //  /yyy/out/foo/bar

        ArrayList<Path> results = new ArrayList<>();
        if(!Files.exists(outDir))
            return results;

        String shortName = javaFile.getFileName().toString();  // Xyz.java
        assert shortName.endsWith(".java");
        shortName = shortName.substring(0, shortName.length() - 5); // Xyz

        // all Xyz[$??].class under outDir.
        try(DirectoryStream<Path> ds = Files.newDirectoryStream(outDir))
        {
            for(Path classFile : ds)
            {
                String fn = classFile.getFileName().toString();
                if(fn.startsWith(shortName) && fn.endsWith(".class"))  // Xyz*.class
                {
                    char c = fn.charAt(shortName.length());
                    if(c=='.' || c=='$')   // Xyz.class or Xyz$*.class
                        results.add(classFile);
                }
            }
        }
        return results;
    }

}
