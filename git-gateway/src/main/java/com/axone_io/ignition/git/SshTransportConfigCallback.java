package com.axone_io.ignition.git;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SshTransportConfigCallback implements TransportConfigCallback {
    private static final Logger logger = LoggerFactory.getLogger(SshTransportConfigCallback.class);

    String sshKey;

    public SshTransportConfigCallback(String sshKey) {
        this.sshKey = sshKey;
    }

    private final SshSessionFactory sshSessionFactory = new JschConfigSessionFactory() {
        @Override
        protected void configure(OpenSshConfig.Host hc, Session session) {
            session.setConfig("StrictHostKeyChecking", "no");
        }

        @Override
        protected JSch createDefaultJSch(FS fs) throws JSchException {
            JSch jSch = super.createDefaultJSch(fs);
            jSch.addIdentity("identity", sshKey.getBytes(), null, null);
            return jSch;
        }
    };

    /**
     * Attach the SSH session factory, but only to a transport that can take one.
     *
     * <p>Whether this callback is installed at all is decided by
     * {@code GitProjectsConfigRecord.isSSHAuthentication()}, which infers the auth mode from the
     * repository URI: anything not starting with {@code http} is treated as SSH. A remote that is
     * neither HTTP(S) nor SSH — a bare local path or {@code file://} URI, as used by test
     * harnesses and local mirrors — therefore arrives here as a {@code TransportLocal} and used to
     * blow up with:</p>
     *
     * <pre>class org.eclipse.jgit.transport.TransportLocal cannot be cast to
     * class org.eclipse.jgit.transport.SshTransport</pre>
     *
     * <p>That aborted the entire operation — pull, push, or a hotfix pipeline mid-flight — over a
     * transport that needs no credentials in the first place. Non-SSH transports are now left
     * untouched.</p>
     */
    @Override
    public void configure(Transport transport) {
        if (transport instanceof SshTransport sshTransport) {
            sshTransport.setSshSessionFactory(sshSessionFactory);
            return;
        }
        logger.debug("Transport {} is not an SshTransport; skipping SSH session configuration",
                transport == null ? "null" : transport.getClass().getSimpleName());
    }
}
