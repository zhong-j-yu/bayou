package _bayou._tmp;

import _bayou._log._Logger;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.*;

// we may make this class public. it might be helpful to users.

// monitor interesting files under some src dirs.
// interesting files: files matching a given path matcher. no dir.
// queries:
//    getAllFiles()      to get all interesting files currently under the dirs
//    pollFileChanges()  to get changes since last successful query. (if no prev successful query, return all files)
// returned paths are all absolute normalized.
// spurious "mod" event is possible - file is not actually modified, and reported as modified by pollFileChanges()


// there are 2 mechanisms to learn about files under dirs
// 1. scan all dirs recursively, find all interesting files.
//    diff with prev result (by date) to get changes.
// 2. poll events from a watcher, get file changes.
//    add to prev result to get all files.

// at first we'll scan dirs; then we'll use a watcher. if watcher fails, we'll scan dirs again.
// IO problems will not corrupt this monitor; user can fix the problems then query again.

// if there's any change in directory structure, we'll abort watcher and rescan.
//   dir events are not well understood. different among OSes. difficult to handle.
// therefore queries are efficient if there were only file changes, no dir changes, since last query.
// good for : short edit-save-refresh cycles; playing around with app.
// if called after long coding period with many changes, a re-scan is more likely.
// scan dir isn't very slow either.

// about watcher: by default we watch top src dirs and all sub dirs recursively. that's lame but the
// only documented way. however on windows that's problematic.
// sun bug#6972833: on windows if a sub dir is watched by us, it's parent cannot be renamed by user.
// workaround: watch root src dirs only, with Sun's undocumented option ExtendedWatchEventModifier.FILE_TREE
// which means automatically watching all sub dirs. this way is actually nicer, but not standard.



// LIMITATIONS

// behavior undefined if a src dir is deleted/renamed. the monitor is likely defunct,
// and the problem may not be recoverable by restoring the src dir.

// only tested on windows and linux

// windows: tested with normal dir/file; links (symbolic or hard) are not tested, behavior unknown to me.

// linux:

// hard link file:
// if file1 is under srcDir, is hard linked to file2, and the file is modified as file2,
//    won't receive event on file1.

// (hard link dir not allowed on linux)

// sym link file:
// modify the real file, even thru the sym link, we won't receive event.
//    (can "touch -h" to touch the sym link itself, then we'll get a Modify event)

// sym link dir:
// if 2 dirs are under srcDir, and one is sym link to another, an event under the real dir
//    may be reported only once, to one dir, not both.

// if a good sym link (file or dir) becomes broken, i.e. its target is deleted/renamed,
//   we may not receive the event.
// (if a sym link is broken at the start (when the monitor is created), it'll be ignored.)
// (if a broken sym link is created later, it'll be ignored.)


public class _FileMonitor implements Closeable
{
    static Consumer<CharSequence> msgOut_stderr = new _PrefixedMsgOut(System.err, "FileMonitor Warning: ");

    boolean trace=false;

    final Object lock = new Object();

    final Consumer<CharSequence> msgOut;

    final PathMatcher matcher;
    final Path[] srcDirs;

    boolean closed;
    WatchService watcher = null; // started by scanDirs(). could be closed and null-ed
    HashSet<Path> dirs;          // all dirs at the time watcher was created.

    HashMap<Path, Long> prevFileDates =new HashMap<>(); // of the last successful query

    public _FileMonitor(PathMatcher matcher, Path... srcDirs)
    {
        this(msgOut_stderr, matcher, srcDirs);
    }

    // srcDir can be relative.
    // note: ~ (linux home dir) doesn't work
    public _FileMonitor(Consumer<CharSequence> msgOut, PathMatcher matcher, Path... srcDirs)
    {
        this.msgOut = msgOut;
        this.matcher = matcher;

        this.srcDirs = new Path[srcDirs.length];
        for(int i=0; i<srcDirs.length; i++)
            this.srcDirs[i] = srcDirs[i].toAbsolutePath().normalize();

        // we don't enforce here that src dirs exist and are really dirs.
        // if not, queries will fail; but the problem is recoverable after user created the dirs.
        // so it's ok if user initially forgot to create the dir. we'll just give warning.
        // however, if a src dir is deleted/renamed later, the problem is more severe.

        for(Path srcDir : srcDirs)
            if(!Files.isDirectory(srcDir))
                msgOut.accept("not a directory: " + srcDir);

    }

    public void close()
    {
        synchronized (lock)
        {
            if(closed)
                return;
            closed=true;

            if(watcher!=null)
            {
                close(watcher);
                watcher = null;
            }
        }
    }

    @Override
    protected void finalize() throws Throwable
    {
        super.finalize();

        // if we don't close watcher, watcher has a live thread that references itself, not hot-reload friendly.
        WatchService watcher = this.watcher;
        if(watcher!=null)
            close(watcher);
    }

    // msgOut can be null
    public Set<Path> getAllFiles() throws Exception
    {
        synchronized (lock)
        {
            if(closed)
                throw new IOException("closed");

            pollFileChanges(); // to bring prevFileDates up to date

            Set<Path> allFiles = prevFileDates.keySet();
            return new HashSet<>( allFiles ); // copy
        }
    }

    // return 3 sets, [0]=created, [1]=modified, [2]=deleted
    // msgOut can be null
    List<Set<Path>> internal_pollFileChanges(long timeout) throws IOException, InterruptedException
    {
        synchronized (lock)
        {
            if(closed)
                throw new IOException("closed");

            if(watcher==null)
                return scanDirs(); // if successful, it creates the watcher

            try
            {
                return pollWatcher(timeout); // if fail, try scanDirs()
            }
            catch(InterruptedException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                if(trace) System.out.println("abort watcher: "+e.toString());
                close(watcher);
                watcher = null;
                dirs = null;

                return scanDirs(); // may throw
            }
        }
    }

    // will not wait for a change event. return quickly if there's no change.
    // if this is 1st query, return all files.
    public List<Set<Path>> pollFileChanges() throws Exception
    {
        return internal_pollFileChanges(-1);
    }
    // will wait till at least one change event occurs, or timeout expires.
    // if this is 1st query, no wait; just return all files.
    public List<Set<Path>> pollFileChanges(long timeout) throws IOException, InterruptedException
    {
        return internal_pollFileChanges(timeout);
    }





    static final WatchEvent.Modifier MOD_FILE_TREE;
    static
    {
        WatchEvent.Modifier result;
        try
        {
            Class clazz = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier");
            @SuppressWarnings("unchecked")
            WatchEvent.Modifier FT = (WatchEvent.Modifier)Enum.valueOf(clazz, "FILE_TREE");

            result = FT;
        }
        catch (Exception|Error t)  // ok, it doesn't exist on this JRE.
        {
            result = null;
        }
        MOD_FILE_TREE = result;
    }

    // scan all dirs recursively, register dirs for watch, collect interesting files
    // return file changes
    List<Set<Path>> scanDirs() throws IOException
    {
        final WatchService watchServiceL = FileSystems.getDefault().newWatchService(); // throws
        final HashSet<Path> dirsL = new HashSet<>();

        // by default we register roots and all sub dirs.
        // special case for windows & sun jdk:
        //     if watch service is sun.nio.fs.WindowsWatchService, register only root dirs
        //     with option com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE
        final boolean isWindows = MOD_FILE_TREE!=null &&
            watchServiceL.getClass().getName().equals("sun.nio.fs.WindowsWatchService");

        final HashMap<Path, Long> currFileDates=new HashMap<>();
        final WatchEvent.Kind<?>[] CREATE_DELETE_MODIFY = {ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY};

        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>()
        {
            // register every directory for watch
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                if(trace) System.out.println("preVisitDirectory "+dir);
                // dir can be a symbolic path
                dirsL.add(dir);
                if(!isWindows) // on windows don't register sub dirs. see later code for registering root dirs.
                    dir.register(watchServiceL, CREATE_DELETE_MODIFY);

                return FileVisitResult.CONTINUE;
            }
            // collect interesting files
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                if(trace) System.out.println("visitFile "+file);
                // file can be a symbolic path
                // if target exists and is a file, the attr is of the real file.
                // if target doesn't exist, this is a broken sym link, the attr is of the sym link itself.
                if(!attrs.isRegularFile())
                {
                    // probably a broken sym link
                    msgOut.accept("unknown file type. ignored: " + file);
                    return FileVisitResult.CONTINUE;
                }
                // a regular file
                if(matcher.matches(file))
                {
                    Long currDate = attrs.lastModifiedTime().toMillis();
                    currFileDates.put(file, currDate);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException
            {
                // e.g. it's a dir that vm has no access right to
                // not critical error. treat it as if it doesn't exist.
                msgOut.accept("" + exc.toString());
                msgOut.accept("visitFileFailed(), file ignored: " + file);
                return FileVisitResult.CONTINUE;
            }

        };

        try
        {
            for(Path srcDir : srcDirs)
                if(!Files.isDirectory(srcDir))
                    throw new IOException("not a directory: "+srcDir);

            // srcDir may not be accessible; by the time walkFileTree() is called, it might be deleted
            // (or recreated as a file). see comment after walkFileTree for all cases.

            for(Path srcDir : srcDirs)
            {
                if(isWindows) // register root dirs, with option to watch entire tree (i.e. all sub dirs)
                    srcDir.register(watchServiceL, CREATE_DELETE_MODIFY, MOD_FILE_TREE);

                Files.walkFileTree(srcDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 1024, visitor);
                // if srcDir doesn't exist, or exists as a dir but is un-accessible,
                //   or exists as a sym link to an existing but un-accessible dir,
                //   visitFileFailed() is reached.
                // if srcDir is a file, or is a sym link to a file, or is a broken sym link,
                //   visitFile() is reached. (may even be recognized as an interesting file!)
                //   this is very bad. but it's very unlikely, so we don't check for it.
            }
        }
        catch (Exception t)
        {
            // this monitor is not corrupt, can survive the error.
            // next scan may work after user fix the problem.

            close(watchServiceL);

            throw t;
        }

        // scan is successful
        watcher = watchServiceL;
        dirs = dirsL;

        // diff curr and prev file dates for changes

        if(prevFileDates.isEmpty()) // the very beginning. fast path.
        {
            prevFileDates = currFileDates;
            return _Util.asReadOnlyList(currFileDates.keySet(), no_file(), no_file());
        }

        HashMap<Path, Long> newFiles = new HashMap<>();
        HashMap<Path, Long> modFiles = new HashMap<>();
        for(Map.Entry<Path, Long> entry : currFileDates.entrySet())
        {
            Path path = entry.getKey();
            Long currDate = entry.getValue();
            Long prevDate = prevFileDates.remove(path); // remove!

            if(prevDate==null)
                newFiles.put(path, currDate);
            else if(currDate.longValue() != prevDate.longValue()) // even if date goes backward (e.g. VCS revert)
                modFiles.put(path, currDate);
            // else same date, file unchanged (we think...)
        }
        HashMap<Path, Long> delFiles = new HashMap<>(prevFileDates); // remaining files in prevFileDates are deleted
        // make a copy, instead of delFiles=prevFileDates. prevFileDates capacity is possibly huge.

        prevFileDates = currFileDates;
        return _Util.asReadOnlyList(newFiles.keySet(), modFiles.keySet(), delFiles.keySet());
    }
    static void close(WatchService watchService)
    {
        try
        {   watchService.close();   }
        catch(Exception e)
        {   _Logger.of(_FileMonitor.class).error("%s",e);   }
    }





    // find file changes from watcher events.
    // multiple events (C/M/D) may have occurred for the same path since last query,
    // we need to consolidate them into one effective event (N/C/M/D/X)
    // N means no event; all paths are in N initially.
    // X means a path was deleted then re-created later;
    //    it's not the same as M, since the type of path (dir/file) may have changed.
    // E means error
    static enum EE
    {
        N, C, M, D, X, E;

        // x axis: watcher event: CREATE, MODIFY, DELETE
        static final EE[][] transition = {
                /*N*/ {C, M, D},
                /*C*/ {E, C, N},
                /*M*/ {E, M, D},
                /*D*/ {X, E, E},
                /*X*/ {E, X, D},
        };
    }
    static Set<Path> no_file(){ return Collections.emptySet(); }
    static List<Set<Path>> NO_CHANGE = _Util.asReadOnlyList(no_file(), no_file(), no_file());

    List<Set<Path>> pollWatcher(long timeout) throws IOException, InterruptedException
    {
        WatchKey key = timeout>0?
                watcher.poll(timeout, TimeUnit.MILLISECONDS) :
                watcher.poll();
        if(key==null) // fast path. no event since last query
            return NO_CHANGE;

        HashMap<Path,EE> effectiveEvents = new HashMap<>(); // C M D X
        do  // while watcher.poll()!=null
        {
            Path dir = (Path)key.watchable();
            for (WatchEvent<?> ev: key.pollEvents())
            {
                WatchEvent.Kind kind = ev.kind();
                if(kind == OVERFLOW)  // in curr impl a key only keeps 512 events max.
                    throw new IOException("event overflow");
                @SuppressWarnings("unchecked")
                WatchEvent<Path> event = (WatchEvent<Path>)ev;
                Path path = dir.resolve(event.context());

                EE ee = effectiveEvents.get(path);
                if(ee==null)
                    ee = EE.N;

                int x = (kind==ENTRY_CREATE)? 0 : (kind==ENTRY_MODIFY)? 1 : /*kind==ENTRY_DELETE*/ 2;
                int y = ee.ordinal();
                EE eeNew = EE.transition[y][x];

                if(trace) System.out.printf("%s + %s -> %s : %s %n", ee, kind, eeNew, path);

                if(eeNew==EE.E) // uh?
                    throw new AssertionError("event conflict: "+ee+" + "+kind); // e.g. C+C
                else if(eeNew==EE.N)  // C+D
                    effectiveEvents.remove(path);
                else // C M D X
                    effectiveEvents.put(path, eeNew);
                // the order of events is lost. not important to us
            }

            boolean valid = key.reset();
            if(!valid) // dir deleted. [#d2]
                throw new IOException("dir change: invalid: "+dir);
        }
        while((key = watcher.poll())!=null);  // note: no timeout here

        // now we have all paths that were changed, and the effective event of each (C,M,D,X)
        // we may read disk for curr info about each path (if not D).
        // this is not atomic of course; see comment inside processEvent()
        HashMap<Path, Long> newFiles = new HashMap<>();
        HashMap<Path, Long> modFiles = new HashMap<>();
        HashSet<Path>       delFiles = new HashSet<>();
        for(Map.Entry<Path,EE> entry : effectiveEvents.entrySet())
        {
            Path path = entry.getKey();
            EE ee = entry.getValue();
            processEvent(newFiles, modFiles, delFiles, path, ee); // throws
        }

        prevFileDates.putAll(newFiles);
        prevFileDates.putAll(modFiles);
        for(Path delFile : delFiles)
            prevFileDates.remove(delFile);

        // we are not quite confident with events, so double check prevFileDates by re-scanning dirs.
        if(doubleCheckByScan)
        {
            System.out.println("FileMonitor.doubleCheckByScan()");
            doubleCheckByScan(); // throws IOException AssertionError
        }

        return _Util.asReadOnlyList(newFiles.keySet(), modFiles.keySet(), delFiles);
    }

    private void processEvent(HashMap<Path, Long> newFiles, HashMap<Path, Long> modFiles, HashSet<Path> delFiles,
                              Path path, EE event) throws IOException
    {
        // path can be symbolic

        if(event == EE.D) // can't read disk to learn what it was
        {
            if(dirs.contains(path))
                throw new IOException("dir deleted: "+path); // [#d1]
            else if(prevFileDates.containsKey(path))
                delFiles.add(path);
            // else other un-interesting files were deleted. don't care

            return;
        }

        // C,M,X. path exists. read disk for attr.
        BasicFileAttributes attrs = Files.readAttributes (path, BasicFileAttributes.class);  // throws
        // follow sym link, attrs is of the real path.
        // if path is a broken sym link,
        //   (e.g. a broken link is created(maybe after prev file/dir deleted), or "touch -h" an existing broken link)
        //   readAttributes() throws, will scanDir, then the broken link is ignored with warning.

        Long fileDate = attrs.lastModifiedTime().toMillis();
        // we really want the file attrs at the exact time when the last event is raised.
        // unfortunately we can't. we are reading the current attrs. there is a small time gap.
        // if file was deleted during the gap, read attrs fails, that's good.
        // if we get the attrs of the path, we only care about 2 things: type(dir/file) and lastModified.
        // if type changed during the gap, that's a huge problem, our state is messed up. but it is very unlikely.
        // if lastModified changed during the gap, no problem.

        if(event == EE.C)
        {
            if(prevFileDates.containsKey(path)) // very odd
            {
                // shouldn't happen, but this case is actually observed, probably a bug of someone:
                //    ubuntu gedit: after save, an ENTRY_CREATE event is received.  (no such problem with vi)
                modFiles.put(path, fileDate);
            }
            else if(attrs.isRegularFile())
            {
                if(matcher.matches(path))
                    newFiles.put(path, fileDate);
            }
            else if(attrs.isDirectory())
                throw new IOException("dir created: "+path);
            else // "other" type?
                msgOut.accept("unknown file type. ignored: " + path);
        }
        else if(event == EE.M)
        {
            if(prevFileDates.containsKey(path))
                modFiles.put(path, fileDate);
            // else it's an uninteresting file, or a dir.
            // on win7, when a file is created/deleted under a dir, we get a MODIFY event on the dir(?!)
            // no such behavior on linux. linux raises MODIFY event if we touch/chmod the dir.
            // on both win/linux, rename a dir raises DELETE then CREATE, not a MODIFY.
            // so we don't care about dir MODIFY, ignore.
            // (touch -h can cause M event on a sym link file/dir)
        }
        else if(event == EE.X) // deleted then created again - tho same path, may became a different type.
        {
            if(dirs.contains(path))
                throw new IOException("dir changed: "+path);

            // path was not a dir
            if(attrs.isDirectory())  // deleted, recreated as dir
                throw new IOException("dir created: "+path);

            if(!attrs.isRegularFile()) // uh?
            {
                msgOut.accept("unknown file type. ignored: " + path);
                return;
            }

            // path was not a dir, is a file
            boolean wasInterestingFile = prevFileDates.containsKey(path);
            if(wasInterestingFile) // 1
                modFiles.put(path, fileDate);
            else if(matcher.matches(path)) // 2
                newFiles.put(path, fileDate);
            // 1. interesting file, deleted and recreated. counted as modified.
            // 2. is interesting, but was not interesting. this rare.
            //    e.g. was a broken sym link, ignored. deleted and recreated, as a file, or a valid link to a file

        }
    }

    // cases of deleting a non-root dir
    // real dir
    // 1 dir is renamed. it's key is still valid, #d2 is not reached;
    //   there are delete-create events, #d1 (of its parent) could be reached.
    // 2 real delete. it's key becomes invalid. both #d2 and #d1 could be reached (order uncertain)
    // sym dir, pointing to a real dir outside srcDir
    //   0. delete/rename the sym link itself won't affect key
    // * 1. rename real dir, key still valid, we are unaware of the rename.
    //      if an event under real dir occurs, path will resolve to a non-existing path,
    //      error at readAttributes(), will trigger scanDir
    //   2. delete real dir. #d2 is reached.

    // on both windows and linux, when a root dir (real) is
    // 1. deleted (not renamed), watcher.poll() returns key, the key becomes invalid @ #d2, abort watcher
    // 2. renamed, the key remains valid. tho there are delete-create events on its parent,
    //    we are not watching its parent so we get no events.
    //    so there's no immediate effect. query will see no changes.
    //    when another event occurs under itself or a subdir
    //    we'll get the context `dir` with the old name, `resolve()` will return non-existing file.
    //      if event is C/M/X, readAttr will fail, IOException. rescan, will fail too. that's good.
    //      if event is D, we act as if root dir isn't renamed.
    //          getAllFiles() returns non-deleted files under old root dir name. it should return no file.
    //          getFileChanges() returns few deleted file under old root dir name. it should report all files deleted.
    //        that is bad, caller gets wrong results.
    // in general, we claim, when a root dir disappears, the monitor is defunct, behavior is undefined,
    //    and the problem may not be recoverable after the dir re-appears.




    static boolean doubleCheckByScan = Boolean.getBoolean(_FileMonitor.class.getName()+".doubleCheckByScan");
    void doubleCheckByScan() throws IOException
    {
        final HashMap<Path, Long> currFileDates=new HashMap<>();

        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>()
        {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                if(attrs.isRegularFile() && matcher.matches(file))
                {
                    Long currDate = attrs.lastModifiedTime().toMillis();
                    currFileDates.put(file, currDate);
                }
                return FileVisitResult.CONTINUE;
            }
        };

        for(Path srcDir : srcDirs)
        {
            Files.walkFileTree(srcDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 1024, visitor); // throws
        }

        // scan completes ok
        // curr and prev file dates should match
        // diff curr and prev file dates
        HashMap<Path, Long> modFiles = new HashMap<>();
        HashSet<Path>       delFiles = new HashSet<>();
        for(Map.Entry<Path, Long> entry : prevFileDates.entrySet())
        {
            Path path = entry.getKey();
            Long prevDate = entry.getValue();
            Long currDate = currFileDates.remove(path); // remove!

            if(currDate==null)
                delFiles.add(path);
            else if(currDate.longValue() != prevDate.longValue()) // even if date goes backward (e.g. VCS revert)
                modFiles.put(path, currDate);
            // else file unchanged (we think)
        }
        HashMap<Path, Long> newFiles = currFileDates;  // remaining files in currFileDates are new files

        StringBuilder error= new StringBuilder();
        for(Path path : newFiles.keySet())
            error.append("extra new file: ").append(path).append("\n");
        for(Path path : modFiles.keySet())
            error.append("extra mod file: ").append(path).append("\n");
        for(Path path : delFiles)
            error.append("extra del file: ").append(path).append("\n");
        if(error.length()>0)
            throw new AssertionError("double check failed:\n"+error.toString());
    }

}
