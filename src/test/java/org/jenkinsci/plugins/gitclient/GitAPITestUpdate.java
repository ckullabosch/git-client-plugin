package org.jenkinsci.plugins.gitclient;

import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import org.objenesis.ObjenesisStd;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.io.OutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public abstract class GitAPITestUpdate {

    private final TemporaryDirectoryAllocator temporaryDirectoryAllocator = new TemporaryDirectoryAllocator();

    private LogHandler handler = null;
    private int logCount = 0;
    private static String defaultBranchName = "mast" + "er"; // Intentionally split string
    private static String defaultRemoteBranchName = "origin/" + defaultBranchName;

    private final String remoteMirrorURL = "https://github.com/jenkinsci/git-client-plugin.git";

    protected TaskListener listener;

    private static final String DEFAULT_MIRROR_BRANCH_NAME = "mast" + "er"; // Intentionally split string

    private static final String LOGGING_STARTED = "Logging started";
    private int checkoutTimeout = -1;
    private int submoduleUpdateTimeout = -1;

    protected hudson.EnvVars env = new hudson.EnvVars();

    private static boolean firstRun = true;

    private void assertCheckoutTimeout() {
        if (checkoutTimeout > 0) {
            assertSubstringTimeout("git checkout", checkoutTimeout);
        }
    }

    private void assertSubmoduleUpdateTimeout() {
        if (submoduleUpdateTimeout > 0) {
            assertSubstringTimeout("git submodule update", submoduleUpdateTimeout);
        }
    }

    private void assertSubstringTimeout(final String substring, int expectedTimeout) {
        if (!(w.git instanceof CliGitAPIImpl)) { // Timeout only implemented in CliGitAPIImpl
            return;
        }
        List<String> messages = handler.getMessages();
        List<String> substringMessages = new ArrayList<>();
        List<String> substringTimeoutMessages = new ArrayList<>();
        final String messageRegEx = ".*\\b" + substring + "\\b.*"; // the expected substring
        final String timeoutRegEx = messageRegEx
                + " [#] timeout=" + expectedTimeout + "\\b.*"; // # timeout=<value>
        for (String message : messages) {
            if (message.matches(messageRegEx)) {
                substringMessages.add(message);
            }
            if (message.matches(timeoutRegEx)) {
                substringTimeoutMessages.add(message);
            }
        }
        assertThat(messages, is(not(empty())));
        assertThat(substringMessages, is(not(empty())));
        assertThat(substringTimeoutMessages, is(not(empty())));
        assertEquals(substringMessages, substringTimeoutMessages);
    }

    protected abstract GitClient setupGitAPI(File ws) throws Exception;

    class WorkingArea {
        final File repo;
        final GitClient git;
        boolean bare = false;

        WorkingArea() throws Exception {
            this(temporaryDirectoryAllocator.allocate());
        }

        WorkingArea(File repo) throws Exception {
            this.repo = repo;
            git = setupGitAPI(repo);
            setupProxy(git);
        }

        private void setupProxy(GitClient gitClient)
                throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException
        {
            final String proxyHost = getSystemProperty("proxyHost", "http.proxyHost", "https.proxyHost");
            final String proxyPort = getSystemProperty("proxyPort", "http.proxyPort", "https.proxyPort");
            final String proxyUser = getSystemProperty("proxyUser", "http.proxyUser", "https.proxyUser");
            //final String proxyPassword = getSystemProperty("proxyPassword", "http.proxyPassword", "https.proxyPassword");
            final String noProxyHosts = getSystemProperty("noProxyHosts", "http.noProxyHosts", "https.noProxyHosts");
            if(isBlank(proxyHost) || isBlank(proxyPort)) return;
            ProxyConfiguration proxyConfig = new ObjenesisStd().newInstance(ProxyConfiguration.class);
            setField(ProxyConfiguration.class, "name", proxyConfig, proxyHost);
            setField(ProxyConfiguration.class, "port", proxyConfig, Integer.parseInt(proxyPort));
            setField(ProxyConfiguration.class, "userName", proxyConfig, proxyUser);
            setField(ProxyConfiguration.class, "noProxyHost", proxyConfig, noProxyHosts);
            //Password does not work since a set password results in a "Secret" call which expects a running Jenkins
            setField(ProxyConfiguration.class, "password", proxyConfig, null);
            setField(ProxyConfiguration.class, "secretPassword", proxyConfig, null);
            gitClient.setProxy(proxyConfig);
        }

        private void setField(Class<?> clazz, String fieldName, Object object, Object value)
                throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException
        {
            Field declaredField = clazz.getDeclaredField(fieldName);
            declaredField.setAccessible(true);
            declaredField.set(object, value);
        }

        private String getSystemProperty(String ... keyVariants)
        {
            for(String key : keyVariants) {
                String value = System.getProperty(key);
                if(value != null) return value;
            }
            return null;
        }

        String launchCommand(String... args) throws IOException, InterruptedException {
            return launchCommand(false, args);
        }

        String launchCommand(boolean ignoreError, String... args) throws IOException, InterruptedException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int st = new Launcher.LocalLauncher(listener).launch().pwd(repo).cmds(args).
                    envs(env).stdout(out).join();
            String s = out.toString();
            if (!ignoreError) {
                if (s == null || s.isEmpty()) {
                    s = StringUtils.join(args, ' ');
                }
                assertEquals(s, 0, st); /* Reports full output of failing commands */
            }
            return s;
        }

        String repoPath() {
            return repo.getAbsolutePath();
        }

        GitAPITestUpdate.WorkingArea init() throws IOException, InterruptedException {
            git.init();
            String userName = "root";
            String emailAddress = "root@mydomain.com";
            CliGitCommand gitCmd = new CliGitCommand(git);
            gitCmd.run("config", "user.name", userName);
            gitCmd.run("config", "user.email", emailAddress);
            git.setAuthor(userName, emailAddress);
            git.setCommitter(userName, emailAddress);
            return this;
        }

        GitAPITestUpdate.WorkingArea init(boolean bare) throws IOException, InterruptedException {
            git.init_().workspace(repoPath()).bare(bare).execute();
            return this;
        }

        void tag(String tag) throws IOException, InterruptedException {
            tag(tag, false);
        }

        void tag(String tag, boolean force) throws IOException, InterruptedException {
            if (force) {
                launchCommand("git", "tag", "--force", tag);
            } else {
                launchCommand("git", "tag", tag);
            }
        }

        void commitEmpty(String msg) throws IOException, InterruptedException {
            launchCommand("git", "commit", "--allow-empty", "-m", msg);
        }

        /**
         * Refers to a file in this workspace
         */
        File file(String path) {
            return new File(repo, path);
        }

        boolean exists(String path) {
            return file(path).exists();
        }

        /**
         * Creates a file in the workspace.
         */
        void touch(String path) throws IOException {
            file(path).createNewFile();
        }

        /**
         * Creates a file in the workspace.
         */
        File touch(String path, String content) throws IOException {
            File f = file(path);
            FileUtils.writeStringToFile(f, content, "UTF-8");
            return f;
        }

        void rm(String path) {
            file(path).delete();
        }

        String contentOf(String path) throws IOException {
            return FileUtils.readFileToString(file(path), "UTF-8");
        }

        /**
         * Creates a CGit implementation. Sometimes we need this for testing JGit impl.
         */
        CliGitAPIImpl cgit() throws Exception {
            return (CliGitAPIImpl)Git.with(listener, env).in(repo).using("git").getClient();
        }

        /**
         * Creates a JGit implementation. Sometimes we need this for testing CliGit impl.
         */
        JGitAPIImpl jgit() throws Exception {
            return (JGitAPIImpl)Git.with(listener, env).in(repo).using("jgit").getClient();
        }

        /**
         * Creates a {@link Repository} object out of it.
         */
        FileRepository repo() throws IOException {
            return bare ? new FileRepository(repo) : new FileRepository(new File(repo, ".git"));
        }

        /**
         * Obtain the current HEAD revision
         */
        ObjectId head() throws IOException, InterruptedException {
            return git.revParse("HEAD");
        }

        /**
         * Casts the {@link #git} to {@link IGitAPI}
         */
        IGitAPI igit() {
            return (IGitAPI)git;
        }
    }
    protected WorkingArea w;

    protected WorkingArea clone(String src) throws Exception {
        WorkingArea x = new WorkingArea();
        x.launchCommand("git", "clone", src, x.repoPath());
        WorkingArea clonedArea = new WorkingArea(x.repo);
        clonedArea.launchCommand("git", "config", "user.name", "Vojtěch Zweibrücken-Šafařík");
        clonedArea.launchCommand("git", "config", "user.email", "email.address.from.git.client.plugin.test@example.com");
        return clonedArea;
    }

    @Before
    public void setUp() throws Exception {
        if (firstRun) {
            firstRun = false;
            defaultBranchName = getDefaultBranchName();
            defaultRemoteBranchName = "origin/" + defaultBranchName;
        }
        setTimeoutVisibleInCurrentTest(true);
        checkoutTimeout = -1;
        submoduleUpdateTimeout = -1;
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);
        w = new WorkingArea();
    }

    /**
     * Populate the local mirror of the git client plugin repository.
     * Returns path to the local mirror directory.
     *
     * @return path to the local mirrror directory
     * @throws IOException on I/O error
     * @throws InterruptedException when execption is interrupted
     */
    protected String localMirror() throws IOException, InterruptedException {
        File base = new File(".").getAbsoluteFile();
        for (File f=base; f!=null; f=f.getParentFile()) {
            File targetDir = new File(f, "target");
            if (targetDir.exists()) {
                String cloneDirName = "clone.git";
                File clone = new File(targetDir, cloneDirName);
                if (!clone.exists()) {
                    /* Clone to a temporary directory then move the
                     * temporary directory to the final destination
                     * directory. The temporary directory prevents
                     * collision with other tests running in parallel.
                     * The atomic move after clone completion assures
                     * that only one of the parallel processes creates
                     * the final destination directory.
                     */
                    Path tempClonePath = Files.createTempDirectory(targetDir.toPath(), "clone-");
                    w.launchCommand("git", "clone", "--reference", f.getCanonicalPath(), "--mirror", "https://github.com/jenkinsci/git-client-plugin", tempClonePath.toFile().getAbsolutePath());
                    if (!clone.exists()) { // Still a race condition, but a narrow race handled by Files.move()
                        renameAndDeleteDir(tempClonePath, cloneDirName);
                    } else {
                        /*
                         * If many unit tests run at the same time and
                         * are using the localMirror, multiple clones
                         * will happen.  All but one of the clones
                         * will be discarded.  The tests reduce the
                         * likelihood of multiple concurrent clones by
                         * adding a random delay to the start of
                         * longer running tests that use the local
                         * mirror.  The delay was enough in my tests
                         * to prevent the duplicate clones and the
                         * resulting discard of the results of the
                         * clone.
                         *
                         * Different processor configurations with
                         * different performance characteristics may
                         * still have parallel tests which attempt to
                         * clone the local mirror concurrently. If
                         * parallel clones happen, only one of the
                         * parallel clones will 'win the race'.  The
                         * deleteRecursive() will discard a clone that
                         * 'lost the race'.
                         */
                        Util.deleteRecursive(tempClonePath.toFile());
                    }
                }
                return clone.getPath();
            }
        }
        throw new IllegalStateException();
    }

    private void renameAndDeleteDir(Path srcDir, String destDirName) {
        try {
            // Try an atomic move first
            Files.move(srcDir, srcDir.resolveSibling(destDirName), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // If Atomic move is not supported, try a move, display exception on failure
            try {
                Files.move(srcDir, srcDir.resolveSibling(destDirName));
            } catch (IOException ioe) {
                Util.displayIOException(ioe, listener);
            }
        } catch (FileAlreadyExistsException ignored) {
            // Intentionally ignore FileAlreadyExists, another thread or process won the race
        } catch (IOException ioe) {
            Util.displayIOException(ioe, listener);
        } finally {
            try {
                Util.deleteRecursive(srcDir.toFile());
            } catch (IOException ioe) {
                Util.displayIOException(ioe, listener);
            }
        }
    }

    private List<File> tempDirsToDelete = new ArrayList<>();

    @After
    public void tearDown() throws Exception {
        try {
            temporaryDirectoryAllocator.dispose();
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue("Logging not started: " + messages, handler.containsMessageSubstring(LOGGING_STARTED));
            assertCheckoutTimeout();
            assertSubmoduleUpdateTimeout();
        } finally {
            handler.close();
        }
        try {
            for (File tempdir : tempDirsToDelete) {
                Util.deleteRecursive(tempdir);
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
        } finally {
            tempDirsToDelete = new ArrayList<>();
        }
    }

    protected abstract boolean hasWorkingGetRemoteSymbolicReferences();
    protected abstract String getRemoteBranchPrefix();

    @Deprecated
    @Test
    public void testPushDeprecatedSignature() throws Exception {
        /* Make working repo a remote of the bare repo */
        w.init();
        w.commitEmpty("init");
        ObjectId workHead = w.head();

        /* Create a bare repo */
        WorkingArea bare = new WorkingArea();
        bare.init(true);

        /* Set working repo origin to point to bare */
        w.git.setRemoteUrl("origin", bare.repoPath());
        assertEquals("Wrong remote URL", w.git.getRemoteUrl("origin"), bare.repoPath());

        /* Push to bare repo */
        w.git.push("origin", defaultBranchName);
        /* JGitAPIImpl revParse fails unexpectedly when used here */
        ObjectId bareHead = w.git instanceof CliGitAPIImpl ? bare.head() : ObjectId.fromString(bare.launchCommand("git", "rev-parse", defaultBranchName).substring(0, 40));
        assertEquals("Heads don't match", workHead, bareHead);
        assertEquals("Heads don't match", w.git.getHeadRev(w.repoPath(), defaultBranchName), bare.git.getHeadRev(bare.repoPath(), defaultBranchName));

        /* Commit a new file */
        w.touch("file1");
        w.git.add("file1");
        w.git.commit("commit1");

        /* Push commit to the bare repo */
        Config config = new Config();
        config.fromText(w.contentOf(".git/config"));
        RemoteConfig origin = new RemoteConfig(config, "origin");
        w.igit().push(origin, defaultBranchName);

        /* JGitAPIImpl revParse fails unexpectedly when used here */
        ObjectId workHead2 = w.git instanceof CliGitAPIImpl ? w.head() : ObjectId.fromString(w.launchCommand("git", "rev-parse", defaultBranchName).substring(0, 40));
        ObjectId bareHead2 = w.git instanceof CliGitAPIImpl ? bare.head() : ObjectId.fromString(bare.launchCommand("git", "rev-parse", defaultBranchName).substring(0, 40));
        assertEquals("Working SHA1 != bare SHA1", workHead2, bareHead2);
        assertEquals("Working SHA1 != bare SHA1", w.git.getHeadRev(w.repoPath(), defaultBranchName), bare.git.getHeadRev(bare.repoPath(), defaultBranchName));
    }


    private void assertExceptionMessageContains(GitException ge, String expectedSubstring) {
        String actual = ge.getMessage().toLowerCase();
        assertTrue("Expected '" + expectedSubstring + "' exception message, but was: " + actual, actual.contains(expectedSubstring));
    }

    public void assertFixSubmoduleUrlsThrows() throws InterruptedException {
        try {
            w.igit().fixSubmoduleUrls("origin", listener);
            fail("Expected exception not thrown");
        } catch (UnsupportedOperationException uoe) {
            assertTrue("Unsupported operation not on JGit", w.igit() instanceof JGitAPIImpl);
        } catch (GitException ge) {
            assertTrue("GitException not on CliGit", w.igit() instanceof CliGitAPIImpl);
            assertTrue("Wrong message in " + ge.getMessage(), ge.getMessage().startsWith("Could not determine remote"));
            assertExceptionMessageContains(ge, "origin");
        }
    }

    private File createTempDirectoryWithoutSpaces() throws IOException {
        // JENKINS-56175 notes that the plugin does not support submodule URL's
        // which contain a space character. Parent pom 3.36 and later use a
        // temporary directory containing a space to detect these problems.
        // Not yet ready to solve JENKINS-56175, so this dodges the problem by
        // creating the submodule repository in a path which does not contain
        // space characters.
        Path tempDirWithoutSpaces = Files.createTempDirectory("no-spaces");
        assertThat(tempDirWithoutSpaces.toString(), not(containsString(" ")));
        tempDirsToDelete.add(tempDirWithoutSpaces.toFile());
        return tempDirWithoutSpaces.toFile();
    }

    @Deprecated
    @Test
    public void testLsTreeNonRecursive() throws IOException, InterruptedException {
        w.init();
        w.touch("file1", "file1 fixed content");
        w.git.add("file1");
        w.git.commit("commit1");
        String expectedBlobSHA1 = "3f5a898e0c8ea62362dbf359cf1a400f3cfd46ae";
        List<IndexEntry> tree = w.igit().lsTree("HEAD", false);
        assertEquals("Wrong blob sha1", expectedBlobSHA1, tree.get(0).getObject());
        assertEquals("Wrong number of tree entries", 1, tree.size());
        final String remoteUrl = localMirror();
        w.igit().setRemoteUrl("origin", remoteUrl, w.repoPath() + File.separator + ".git");
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong invalid default remote", "origin", w.igit().getDefaultRemote("invalid"));
    }

    @NotImplementedInJGit
    @Test
    public void testTrackingSubmodule() throws Exception {
        if (! ((CliGitAPIImpl)w.git).isAtLeastVersion(1,8,2,0)) {
            System.err.println("git must be at least 1.8.2 to do tracking submodules.");
            return;
        }
        w.init(); // empty repository

        // create a new GIT repo.
        //   default branch -- <file1>C  <file2>C
        WorkingArea r = new WorkingArea(createTempDirectoryWithoutSpaces());
        r.init();
        r.touch("file1", "content1");
        r.git.add("file1");
        r.git.commit("submod-commit1");

        // Add new GIT repo to w
        String subModDir = "submod1-" + UUID.randomUUID();
        w.git.addSubmodule(r.repoPath(), subModDir);
        w.git.submoduleInit();

        // Add a new file to the separate GIT repo.
        r.touch("file2", "content2");
        r.git.add("file2");
        r.git.commit("submod-branch1-commit1");

        // Make sure that the new file doesn't exist in the repo with remoteTracking
        String subFile = subModDir + File.separator + "file2";
        w.git.submoduleUpdate().recursive(true).remoteTracking(false).execute();
        assertFalse("file2 exists and should not because we didn't update to the tip of the default branch.", w.exists(subFile));

        // Windows tests fail if repoPath is more than 200 characters
        // CLI git support for longpaths on Windows is complicated
        if (w.repoPath().length() > 200) {
            return;
        }

        // Run submodule update with remote tracking
        w.git.submoduleUpdate().recursive(true).remoteTracking(true).execute();
        assertTrue("file2 does not exist and should because we updated to the tip of the default branch.", w.exists(subFile));
        assertFixSubmoduleUrlsThrows();
    }


    private String getDefaultBranchName() throws Exception {
        String defaultBranchValue = "mast" + "er"; // Intentionally split to note this will remain
        File configDir = Files.createTempDirectory("readGitConfig").toFile();
        CliGitCommand getDefaultBranchNameCmd = new CliGitCommand(Git.with(TaskListener.NULL, env).in(configDir).using("git").getClient());
        String[] output = getDefaultBranchNameCmd.runWithoutAssert("config", "--get", "init.defaultBranch");
        for (String s : output) {
            String result = s.trim();
            if (result != null && !result.isEmpty()) {
                defaultBranchValue = result;
            }
        }
        assertTrue("Failed to delete temporary readGitConfig directory", configDir.delete());
        return defaultBranchValue;
    }

    private void extract(ZipFile zipFile, File outputDir) throws IOException
    {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            File entryDestination = new File(outputDir,  entry.getName());
            entryDestination.getParentFile().mkdirs();
            if (entry.isDirectory())
                entryDestination.mkdirs();
            else {
                try (InputStream in = zipFile.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(entryDestination.toPath())) {
                    org.apache.commons.io.IOUtils.copy(in, out);
                }
            }
        }
    }

    private void checkHeadRev(String repoURL, ObjectId expectedId) throws InterruptedException {
        final ObjectId originDefaultBranch = w.git.getHeadRev(repoURL, DEFAULT_MIRROR_BRANCH_NAME);
        assertEquals("origin default branch mismatch", expectedId, originDefaultBranch);

        final ObjectId simpleDefaultBranch = w.git.getHeadRev(repoURL, DEFAULT_MIRROR_BRANCH_NAME);
        assertEquals("simple default branch mismatch", expectedId, simpleDefaultBranch);

        final ObjectId wildcardSCMDefaultBranch = w.git.getHeadRev(repoURL, "*/" + DEFAULT_MIRROR_BRANCH_NAME);
        assertEquals("wildcard SCM default branch mismatch", expectedId, wildcardSCMDefaultBranch);

        /* This assertion may fail if the localMirror has more than
         * one branch matching the wildcard expression in the call to
         * getHeadRev.  The expression is chosen to be unlikely to
         * match with typical branch names, while still matching a
         * known branch name. Should be fine so long as no one creates
         * branches named like main-default-branch or new-default-branch on the
         * remote repo.
         * 'origin/main' becomes 'origin/m*i?'
         */
        final ObjectId wildcardEndDefaultBranch = w.git.getHeadRev(repoURL, DEFAULT_MIRROR_BRANCH_NAME.replace('a', '*').replace('t', '?').replace('n', '?'));
        assertEquals("wildcard end default branch mismatch", expectedId, wildcardEndDefaultBranch);
    }

    private boolean timeoutVisibleInCurrentTest;

    /**
     * Returns true if the current test is expected to have a timeout
     * value visible written to the listener log.  Used to assert
     * timeout values are passed correctly through the layers without
     * requiring that the timeout actually expire.
     * @see #setTimeoutVisibleInCurrentTest(boolean)
     */
    protected boolean getTimeoutVisibleInCurrentTest() {
        return timeoutVisibleInCurrentTest;
    }

    protected void setTimeoutVisibleInCurrentTest(boolean visible) {
        timeoutVisibleInCurrentTest = visible;
    }

    /**
     * Test getRemoteReferences with matching pattern
     */
    @Test
    public void testGetRemoteReferencesWithMatchingPattern() throws Exception {
        Map<String, ObjectId> references = w.git.getRemoteReferences(remoteMirrorURL, "refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME, true, false);
        assertTrue(references.containsKey("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
        assertFalse(references.containsKey("refs/tags/git-client-1.0.0"));
        references = w.git.getRemoteReferences(remoteMirrorURL, "git-client-*", false, true);
        assertFalse(references.containsKey("refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME));
        for (String key : references.keySet()) {
            assertTrue(key.startsWith("refs/tags/git-client"));
        }

        references = new HashMap<>();
        try {
            references = w.git.getRemoteReferences(remoteMirrorURL, "notexists-*", false, false);
        } catch (GitException ge) {
            assertExceptionMessageContains(ge, "unexpected ls-remote output");
        }
        assertTrue(references.isEmpty());
    }

    @Test
    public void testGetHeadRevRemote() throws Exception {
        String lsRemote = w.launchCommand("git", "ls-remote", "-h", remoteMirrorURL, "refs/heads/" + DEFAULT_MIRROR_BRANCH_NAME);
        ObjectId lsRemoteId = ObjectId.fromString(lsRemote.substring(0, 40));
        checkHeadRev(remoteMirrorURL, lsRemoteId);
    }

    @Test
    public void testHasGitRepoWithoutGitDirectory() throws Exception
    {
        setTimeoutVisibleInCurrentTest(false);
        assertFalse("Empty directory has a Git repo", w.git.hasGitRepo());
    }

    @Issue("JENKINS-22343")
    @Test
    public void testShowRevisionForFirstCommit() throws Exception {
        w.init();
        w.touch("a");
        w.git.add("a");
        w.git.commit("first");
        ObjectId first = w.head();
        List<String> revisionDetails = w.git.showRevision(first);
        List<String> commits =
                revisionDetails.stream()
                        .filter(detail -> detail.startsWith("commit "))
                        .collect(Collectors.toList());
        assertTrue("Commits '" + commits + "' missing " + first.getName(), commits.contains("commit " + first.getName()));
        assertEquals("Commits '" + commits + "' wrong size", 1, commits.size());
    }

    /**
     * Test parsing of changelog with unicode characters in commit messages.
     */
    @Deprecated
    @Test
    public void testReset() throws IOException, InterruptedException {
        w.init();
        /* No valid HEAD yet - nothing to reset, should give no error */
        w.igit().reset(false);
        w.igit().reset(true);
        w.touch("committed-file", "committed-file content " + UUID.randomUUID());
        w.git.add("committed-file");
        w.git.commit("commit1");
        assertTrue("committed-file missing at commit1", w.file("committed-file").exists());
        assertFalse("added-file exists at commit1", w.file("added-file").exists());
        assertFalse("touched-file exists at commit1", w.file("added-file").exists());

        w.launchCommand("git", "rm", "committed-file");
        w.touch("added-file", "File 2 content " + UUID.randomUUID());
        w.git.add("added-file");
        w.touch("touched-file", "File 3 content " + UUID.randomUUID());
        assertFalse("committed-file exists", w.file("committed-file").exists());
        assertTrue("added-file missing", w.file("added-file").exists());
        assertTrue("touched-file missing", w.file("touched-file").exists());

        w.igit().reset(false);
        assertFalse("committed-file exists", w.file("committed-file").exists());
        assertTrue("added-file missing", w.file("added-file").exists());
        assertTrue("touched-file missing", w.file("touched-file").exists());

        w.git.add("added-file"); /* Add the file which soft reset "unadded" */

        w.igit().reset(true);
        assertTrue("committed-file missing", w.file("committed-file").exists());
        assertFalse("added-file exists at hard reset", w.file("added-file").exists());
        assertTrue("touched-file missing", w.file("touched-file").exists());

        final String remoteUrl = "git@github.com:MarkEWaite/git-client-plugin.git";
        w.git.setRemoteUrl("origin", remoteUrl);
        w.git.setRemoteUrl("ndeloof", "git@github.com:ndeloof/git-client-plugin.git");
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong ndeloof default remote", "ndeloof", w.igit().getDefaultRemote("ndeloof"));
        /* CliGitAPIImpl and JGitAPIImpl return different ordered lists for default remote if invalid */
        assertEquals("Wrong invalid default remote", w.git instanceof CliGitAPIImpl ? "ndeloof" : "origin",
                w.igit().getDefaultRemote("invalid"));
    }


    @Issue({"JENKINS-6203", "JENKINS-14798", "JENKINS-23091"})
    @Test
    public void testUnicodeCharsInChangelog() throws Exception {
        File tempRemoteDir = temporaryDirectoryAllocator.allocate();
        extract(new ZipFile("src/test/resources/unicodeCharsInChangelogRepo.zip"), tempRemoteDir);
        File pathToTempRepo = new File(tempRemoteDir, "unicodeCharsInChangelogRepo");
        w = clone(pathToTempRepo.getAbsolutePath());

        // w.git.changelog gives us strings
        // We want to collect all the strings and check that unicode characters are still there.

        StringWriter sw = new StringWriter();
        w.git.changelog("v0", "vLast", sw);
        String content = sw.toString();

        assertTrue(content.contains("hello in English: hello"));
        assertTrue(content.contains("hello in Russian: \u043F\u0440\u0438\u0432\u0435\u0442 (priv\u00E9t)"));
        assertTrue(content.contains("hello in Chinese: \u4F60\u597D (n\u01D0 h\u01CEo)"));
        assertTrue(content.contains("hello in French: \u00C7a va ?"));
        assertTrue(content.contains("goodbye in German: Tsch\u00FCss"));
    }

    @Deprecated
    @Test
    public void testGetDefaultRemote() throws Exception {
        w.init();
        w.launchCommand("git", "remote", "add", "origin", "https://github.com/jenkinsci/git-client-plugin.git");
        w.launchCommand("git", "remote", "add", "ndeloof", "git@github.com:ndeloof/git-client-plugin.git");
        assertEquals("Wrong origin default remote", "origin", w.igit().getDefaultRemote("origin"));
        assertEquals("Wrong ndeloof default remote", "ndeloof", w.igit().getDefaultRemote("ndeloof"));
        /* CliGitAPIImpl and JGitAPIImpl return different ordered lists for default remote if invalid */
        assertEquals("Wrong invalid default remote", w.git instanceof CliGitAPIImpl ? "ndeloof" : "origin",
                w.igit().getDefaultRemote("invalid"));
    }

    /**
     * Multi-branch pipeline plugin and other AbstractGitSCMSource callers were
     * initially using JGit as their implementation, and developed an unexpected
     * dependency on JGit behavior. JGit init() (in JGit 3.7 at least) creates
     * the directory if it does not exist. Rather than change the multi-branch
     * pipeline when the git client plugin was adapted to allow either git or
     * jgit, instead the git.init() method was changed to create the target
     * directory if it does not exist.
     *
     * Low risk from that change of behavior, since a non-existent directory
     * caused the command line git init() method to consistently throw an
     * exception.
     *
     * @throws java.lang.Exception on error
     */
    @Test
    public void testGitInitCreatesDirectoryIfNeeded() throws Exception {
        File nonexistentDir = new File(UUID.randomUUID().toString());
        assertFalse("Dir unexpectedly exists at start of test", nonexistentDir.exists());
        try {
            GitClient git = setupGitAPI(nonexistentDir);
            git.init();
        } finally {
            FileUtils.deleteDirectory(nonexistentDir);
        }
    }
}
