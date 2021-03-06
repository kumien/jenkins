package jenkins.slaves;

import hudson.AbortException;
import hudson.Extension;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.remoting.Engine;
import hudson.slaves.SlaveComputer;
import jenkins.AgentProtocol;
import jenkins.model.Jenkins;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link AgentProtocol} that accepts connection from slave agents.
 *
 * <h2>Security</h2>
 * <p>
 * Once connected, remote slave agents can send in commands to be
 * executed on the master, so in a way this is like an rsh service.
 * Therefore, it is important that we reject connections from
 * unauthorized remote slaves.
 *
 * <p>
 * The approach here is to have {@link Jenkins#getSecretKey() a secret key} on the master.
 * This key is sent to the slave inside the <tt>.jnlp</tt> file
 * (this file itself is protected by HTTP form-based authentication that
 * we use everywhere else in Hudson), and the slave sends this
 * token back when it connects to the master.
 * Unauthorized slaves can't access the protected <tt>.jnlp</tt> file,
 * so it can't impersonate a valid slave.
 *
 * <p>
 * We don't want to force the JNLP slave agents to be restarted
 * whenever the server restarts, so right now this secret master key
 * is generated once and used forever, which makes this whole scheme
 * less secure.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.467
 */
@Extension
public class JnlpSlaveAgentProtocol extends AgentProtocol {
    @Override
    public String getName() {
        return "JNLP-connect";
    }

    @Override
    public void handle(Socket socket) throws IOException, InterruptedException {
        new Handler(socket).run();
    }

    protected static class Handler {
        protected final Socket socket;

        /**
         * Wrapping Socket input stream.
         */
        protected final DataInputStream in;

        /**
         * For writing handshaking response.
         *
         * This is a poor design choice that we just carry forward for compatibility.
         * For better protocol design, {@link DataOutputStream} is preferred for newer
         * protocols.
         */
        protected final PrintWriter out;

        public Handler(Socket socket) throws IOException {
            this.socket = socket;
            in = new DataInputStream(socket.getInputStream());
            out = new PrintWriter(socket.getOutputStream(),true);
        }

        protected void run() throws IOException, InterruptedException {
            if(!getSecretKey().equals(in.readUTF())) {
                error(out, "Unauthorized access");
                return;
            }

            final String nodeName = in.readUTF();
            SlaveComputer computer = (SlaveComputer) Jenkins.getInstance().getComputer(nodeName);
            if(computer==null) {
                error(out, "No such slave: "+nodeName);
                return;
            }

            if(computer.getChannel()!=null) {
                error(out, nodeName+" is already connected to this master. Rejecting this connection.");
                return;
            }

            out.println(Engine.GREETING_SUCCESS);

            jnlpConnect(computer);
        }

        protected Channel jnlpConnect(SlaveComputer computer) throws InterruptedException, IOException {
            final String nodeName = computer.getName();
            final OutputStream log = computer.openLogFile();
            PrintWriter logw = new PrintWriter(log,true);
            logw.println("JNLP agent connected from "+ socket.getInetAddress());

            try {
                computer.setChannel(new BufferedInputStream(socket.getInputStream()), new BufferedOutputStream(socket.getOutputStream()), log,
                    new Listener() {
                        @Override
                        public void onClosed(Channel channel, IOException cause) {
                            if(cause!=null)
                                LOGGER.log(Level.WARNING, Thread.currentThread().getName()+" for + " + nodeName + " terminated",cause);
                            try {
                                socket.close();
                            } catch (IOException e) {
                                // ignore
                            }
                        }
                    });
                return computer.getChannel();
            } catch (AbortException e) {
                logw.println(e.getMessage());
                logw.println("Failed to establish the connection with the slave");
                throw e;
            } catch (IOException e) {
                logw.println("Failed to establish the connection with the slave " + nodeName);
                e.printStackTrace(logw);
                throw e;
            }
        }

        protected String getSecretKey() {
            return Jenkins.getInstance().getSecretKey();
        }

        protected void error(PrintWriter out, String msg) throws IOException {
            out.println(msg);
            LOGGER.log(Level.WARNING,Thread.currentThread().getName()+" is aborted: "+msg);
            socket.close();
        }
    }

    private static final Logger LOGGER = Logger.getLogger(JnlpSlaveAgentProtocol.class.getName());
}
