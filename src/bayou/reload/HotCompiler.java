package bayou.reload;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Compiler for HotReloader.
 * <p>
 *     A HotCompiler is registered to a HotReloader through
 *     {@link HotReloader#addCompiler(HotCompiler,boolean,Path...) HotReloader.addCompiler(compiler, reload, srcDirs)}.
 *     The source files for the compiler are those under the <code>srcDirs</code> and matching
 *     {@link #getPathMatcher() the PathMatcher}.
 * </p>
 * <p>
 *     When source files are created/updated/deleted, the changes are fed to the
 *     {@link #compile(Consumer, Set, Set, Set) compile(createdFiles,updatedFiles,deletedFiles)}
 *     method for recompilation. There is also a
 *     {@link #compile(Consumer, Set) compile(allFiles)} method for recompiling all source files.
 * </p>
 * <p>
 *     A HotCompiler usually compiles source files to output files.
 *     However it may do something else, e.g. update an external device after a source file is updated.
 * </p>
 * <p>
 *     An implementation of HotCompiler can be stateful, and not thread-safe.
 *     A HotCompiler instance should not be shared, i.e. it should not be used in multiple
 *     {@link HotReloader#addCompiler(HotCompiler,boolean,Path...) addCompiler()} calls.
 * </p>
 */
public interface HotCompiler
{
    /**
     * The PathMatcher for source files.
     * <p>
     *     Only those files under the {@link HotReloader#addCompiler(HotCompiler,boolean,Path...) srcDirs }
     *     will be fed to this PathMatcher;
     *     only those approved by this PathMatcher are source files for this compiler.
     * </p>
     * <p>
     *     An example PathMatcher:
     *     <code>FileSystems.getDefault().getPathMatcher("glob:**.java")</code>.
     * </p>
     * @see java.nio.file.FileSystem#getPathMatcher(String)
     */
    PathMatcher getPathMatcher();
    // pathMatcher is specified by the HotCompiler instead of by HotReloader.addCompiler()
    //    it's usually an inherent property of the compiler, app may not get it right.
    // a compiler may work on fixed srcDirs (see JavacCompiler). we don't put srcDirs on this interface,
    //    reasoning that it's not unlikely that a compiler works on any given srcDirs,
    //    therefore app should be able to specific srcDirs independent of the compiler.

    /**
     * Compile all source files.
     * <p>
     *     This method is usually called in the initial phase of the HotReloader.
     *     It may also be called whenever the HotReloader thinks necessary.
     * </p>
     * <p>
     *     The compiler does not necessarily do a clean build. For example, it may compare
     *     timestamps of source files and output files, skip source files that are older than the output files.
     * </p>
     * @param  msgOut
     *         for diagnosis messages; see {@link HotReloader#getMessageOut()}
     * @throws Exception
     *         if compilation fails; the exception message usually contains compilation errors.
     */
    void compile(Consumer<CharSequence> msgOut, Set<Path> allFiles) throws Exception;
    // after compile, sources and outputs should be in sync. (should remove undesired outputs from prev compiles)

    /**
     * Compile changed source files.
     * <p>
     *     This method is called if some source files are created/updated/deleted.
     * </p>
     * @param  msgOut
     *         for diagnosis messages; see {@link HotReloader#getMessageOut()}
     * @throws Exception
     *         if compilation fails; the exception message usually contains compilation errors.
     */
    // incremental compile. when possible, builder uses this method to reduce cost.
    void compile(Consumer<CharSequence> msgOut,
                 Set<Path> createdFiles, Set<Path> updatedFiles, Set<Path> deletedFiles)
            throws Exception;
}
