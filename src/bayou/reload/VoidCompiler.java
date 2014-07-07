package bayou.reload;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A HotCompiler that generates no output.
 * <p>
 *     The purpose of a VoidCompiler is to reload the application if some files are changed
 *     (but requiring no recompilation). For example, if the application reads <code>".properties"</code> files
 *     on startup and cache the information in memory, it needs to be reloaded if some of the files are changed.
 * </p>
 * <p>
 *     See also
 *     {@link HotReloader#onFiles(String, String, String...) HotReloader.onFiles(fileDesc, filePattern, srcDirs)}.
 * </p>
 */

// a compiler that does nothing
// primary use: reload app after some files are changed; app reads these files during init.
//    addCompiler(new VoidCompiler("glob:**.properties"), srcDirs);
//    //will reload app if any .properties files are create/updated/deleted under srcDirs.
// to monitor a classes dir, and reload app when classes changes
//    addCompiler(new VoidCompiler("glob:**.class"), classDirs);

public class VoidCompiler implements HotCompiler
{
    final String fileDesc;
    final PathMatcher pathMatcher;

    /**
     * Create a VoidCompiler.
     * <p>
     *     <code>fileDesc</code> is used for diagnosis outputs.
     * </p>
     * <p>
     *     For example:
     *     <code>new VoidCompiler("java", FileSystems.getDefault().getPathMatcher("glob:**.java"))</code>
     * </p>
     */
    public VoidCompiler(String fileDesc, PathMatcher pathMatcher)
    {
        this.fileDesc = fileDesc;
        this.pathMatcher = pathMatcher;
    }

    static VoidCompiler of(String fileDesc, String filePattern)
    {
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher(filePattern);
        return new VoidCompiler(fileDesc, pathMatcher);
    }

    // return a path matcher based on the filePattern
    @Override
    public PathMatcher getPathMatcher()
    {
        return pathMatcher;
    }

    /**
     * Does nothing, except printing some messages to <code>msgOut</code>.
     */
    @Override
    public void compile(Consumer<CharSequence> msgOut, Set<Path> allFiles)
    {
        msgOut.accept(fileDesc + " files: " + allFiles.size() + " total");
    }

    /**
     * Does nothing, except printing some messages to <code>msgOut</code>.
     */
    @Override
    public void compile(Consumer<CharSequence> msgOut,
                        Set<Path> createdFiles, Set<Path> updatedFiles, Set<Path> deletedFiles)
    {
        if(createdFiles.size()>0)
            msgOut.accept(fileDesc + " files: " + createdFiles.size() + " created");
        if(updatedFiles.size()>0)
            msgOut.accept(fileDesc + " files: " + updatedFiles.size() + " updated");
        if(deletedFiles.size()>0)
            msgOut.accept(fileDesc + " files: " + deletedFiles.size() + " deleted");
    }

}
