package com.axone_io.ignition.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The auth mode is inferred from the repository URI — anything not starting with "http" is
 * treated as SSH — so a remote that is neither HTTP(S) nor SSH (a bare local path or file://
 * URI) reaches this callback as a {@code TransportLocal}. The unguarded cast then threw
 * {@code ClassCastException} and aborted the whole operation: a pull, a push, or a hotfix
 * pipeline mid-flight.
 */
public class SshTransportConfigCallbackTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void configuringALocalTransportDoesNotThrow() throws Exception {
        File repoDir = tempFolder.newFolder("work");
        File remoteDir = tempFolder.newFolder("remote.git");

        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir).call();
             Git git = Git.init().setDirectory(repoDir).call()) {

            // A real TransportLocal, exactly as the gateway obtains one for a local-path remote.
            try (Transport transport = Transport.open(git.getRepository(),
                    new URIish(remoteDir.toURI().toString()))) {

                assertFalse("expected a non-SSH transport for a file:// remote",
                        transport instanceof SshTransport);

                // Before the guard this threw ClassCastException.
                new SshTransportConfigCallback("not-a-real-key").configure(transport);
            }
        }
    }

    @Test
    public void nullTransportIsToleratedRatherThanNpe() {
        // configure() is called by JGit, but a defensive skip beats a NullPointerException
        // surfacing as an aborted git operation.
        new SshTransportConfigCallback("not-a-real-key").configure(null);
    }

    @Test
    public void anSshTransportStillGetsTheSessionFactory() throws Exception {
        File repoDir = tempFolder.newFolder("work-ssh");

        try (Git git = Git.init().setDirectory(repoDir).call();
             Transport transport = Transport.open(git.getRepository(),
                     new URIish("ssh://git@example.invalid/org/repo.git"))) {

            assertTrue("ssh:// should yield an SshTransport", transport instanceof SshTransport);

            new SshTransportConfigCallback("not-a-real-key").configure(transport);

            // The factory is what carries the key and the host-key policy; losing it silently
            // would turn every SSH operation into an auth failure.
            assertTrue("session factory should have been applied",
                    ((SshTransport) transport).getSshSessionFactory() != null);
        }
    }
}
