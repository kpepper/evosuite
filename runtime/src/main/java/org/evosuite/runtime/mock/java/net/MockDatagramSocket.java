package org.evosuite.runtime.mock.java.net;

import org.evosuite.runtime.mock.java.io.MockIOException;
import org.evosuite.runtime.mock.java.lang.MockError;
import org.evosuite.runtime.mock.java.lang.MockIllegalArgumentException;

import java.io.IOException;
import java.net.*;
import java.nio.channels.DatagramChannel;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;

/**
 * Created by arcuri on 12/7/14.
 */
public class MockDatagramSocket extends DatagramSocket {

    private static final int ST_NOT_CONNECTED = 0;
    private static final int ST_CONNECTED = 1;
    private static final int ST_CONNECTED_NO_IMPL = 2;

    /*
        following fields are the same as in superclass.
        however, they are not overwritten, as in superclass they
        have private/package level access
     */

    private final Object closeLock = new Object();

    private boolean created = false;
    private boolean bound = false;
    private boolean closed = false;
    private int connectState = ST_NOT_CONNECTED;
    private EvoDatagramSocketImpl impl;
    private InetAddress connectedAddress = null;
    private int connectedPort = -1;

    // there is only one protected constructor that does not do binding
    /*
    protected DatagramSocket(DatagramSocketImpl impl) {
        if (impl == null)
            throw new NullPointerException();
        this.impl = impl;
        checkOldImpl();
    }
    */

    /*
        note: we need to pass to the super(impl) constructor a non-null reference.
        however, such reference is not used in class (we could by using reflection,
        but just easier to make a new copy)
     */

    protected MockDatagramSocket(DatagramSocketImpl impl) throws SocketException {
        super(new EvoDatagramSocketImpl());
        createImpl();
    }

    public MockDatagramSocket() throws SocketException {
        super(new EvoDatagramSocketImpl());

        // create a datagram socket.
        createImpl();
        try {
            bind(new MockInetSocketAddress(0));
        } catch (SocketException se) {
            throw se;
        } catch(IOException e) {
            throw new SocketException(e.getMessage());
        }
    }


    public MockDatagramSocket(SocketAddress bindaddr) throws SocketException {
        super(new EvoDatagramSocketImpl());

        // create a datagram socket.
        createImpl();
        if (bindaddr != null) {
            bind(bindaddr);
        }
    }

    public MockDatagramSocket(int port) throws SocketException {
        this(port, null);
    }


    public MockDatagramSocket(int port, InetAddress laddr) throws SocketException {
        this(new MockInetSocketAddress(laddr, port));
    }


    // -----------------------------------


    /*
        it was package level in superclass
     */
    private void createImpl() throws SocketException {
        if (impl == null) {
                //boolean isMulticast = (this instanceof MulticastSocket) ? true : false;
                //impl = DefaultDatagramSocketImplFactory.createDatagramSocketImpl(isMulticast);
                impl = new EvoDatagramSocketImpl();

        }
        // creates a udp socket
        impl.create();
        created = true;
    }

    private synchronized void connectInternal(InetAddress address, int port) throws SocketException {
        if (port < 0 || port > 0xFFFF) {
            throw new MockIllegalArgumentException("connect: " + port);
        }
        if (address == null) {
            throw new MockIllegalArgumentException("connect: null address");
        }
        checkAddress (address, "connect");

        if (isClosed()) {
            return;
        }

        if (!isBound()) {
            bind(new MockInetSocketAddress(0));
        }

            try {
                getImpl().connect(address, port);

                // socket is now connected by the impl
                connectState = ST_CONNECTED;
            } catch (SocketException se) {
                //NOTE: this should never happen in mock environment
                //
                // connection will be emulated by DatagramSocket
                connectState = ST_CONNECTED_NO_IMPL;
            }

        connectedAddress = address;
        connectedPort = port;
    }


    private EvoDatagramSocketImpl getImpl() throws SocketException {
        if (!created) {
            createImpl();
        }
        return impl;
    }

    @Override
    public synchronized void bind(SocketAddress addr) throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        if (isBound())
            throw new SocketException("already bound");
        if (addr == null)
            addr = new MockInetSocketAddress(0);
        if (!(addr instanceof InetSocketAddress))
            throw new MockIllegalArgumentException("Unsupported address type!");
        InetSocketAddress epoint = (InetSocketAddress) addr;
        if (epoint.isUnresolved())
            throw new SocketException("Unresolved address");
        InetAddress iaddr = epoint.getAddress();
        int port = epoint.getPort();
        checkAddress(iaddr, "bind");

        try {
            getImpl().bind(port, iaddr);
        } catch (SocketException e) {
            getImpl().close();
            throw e;
        }
        bound = true;
    }

    private void checkAddress (InetAddress addr, String op) {
        if (addr == null) {
            return;
        }
        if (!(addr instanceof Inet4Address || addr instanceof Inet6Address)) {
            throw new MockIllegalArgumentException(op + ": invalid address type");
        }
    }

    @Override
    public void connect(InetAddress address, int port) {
        try {
            connectInternal(address, port);
        } catch (SocketException se) {
            throw new MockError("connect failed", se);
        }
    }

    @Override
    public void connect(SocketAddress addr) throws SocketException {
        if (addr == null)
            throw new MockIllegalArgumentException("Address can't be null");
        if (!(addr instanceof InetSocketAddress))
            throw new MockIllegalArgumentException("Unsupported address type");
        InetSocketAddress epoint = (InetSocketAddress) addr;
        if (epoint.isUnresolved())
            throw new SocketException("Unresolved address");
        connectInternal(epoint.getAddress(), epoint.getPort());
    }

    @Override
    public void disconnect() {
        synchronized (this) {
            if (isClosed())
                return;
            if (connectState == ST_CONNECTED) {
                impl.disconnect();
            }
            connectedAddress = null;
            connectedPort = -1;
            connectState = ST_NOT_CONNECTED;
        }
    }

    @Override
    public boolean isBound() {
        return bound;
    }

    @Override
    public boolean isConnected() {
        return connectState != ST_NOT_CONNECTED;
    }

    @Override
    public InetAddress getInetAddress() {
        return connectedAddress;
    }

    @Override
    public int getPort() {
        return connectedPort;
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        if (!isConnected())
            return null;
        return new MockInetSocketAddress(getInetAddress(), getPort());
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        if (isClosed())
            return null;
        if (!isBound())
            return null;
        return new MockInetSocketAddress(getLocalAddress(), getLocalPort());
    }

    @Override
    public void send(DatagramPacket p) throws IOException  {
        InetAddress packetAddress = null;
        synchronized (p) {
            if (isClosed())
                throw new SocketException("Socket is closed");
            checkAddress (p.getAddress(), "send");
            if (connectState == ST_NOT_CONNECTED) {
                // check the address is ok wiht the security manager on every send.
            } else {
                // we're connected
                packetAddress = p.getAddress();
                if (packetAddress == null) {
                    p.setAddress(connectedAddress);
                    p.setPort(connectedPort);
                } else if ((!packetAddress.equals(connectedAddress)) ||
                        p.getPort() != connectedPort) {
                    throw new MockIllegalArgumentException("connected address and packet address differ");
                }
            }

            // Check whether the socket is bound
            if (!isBound())
                bind(new MockInetSocketAddress(0));
            // call the  method to send
            getImpl().send(p);
        }
    }

    @Override
    public synchronized void receive(DatagramPacket p) throws IOException {
        synchronized (p) {
            if (!isBound())
                bind(new MockInetSocketAddress(0));
            if (connectState == ST_CONNECTED_NO_IMPL) {
                /* We have to do the filtering the old fashioned way since
                 the native impl doesn't support connect or the connect
                 via the impl failed.

                    However, in mock there is no need to simulate such behavior
                */
            }
            getImpl().receive(p);
        }
    }

    @Override
    public InetAddress getLocalAddress() {
        if (isClosed())
            return null;
        InetAddress in = null;
        try {
            in = (InetAddress) getImpl().getOption(SocketOptions.SO_BINDADDR);
            if (in.isAnyLocalAddress()) {
                in = MockInetAddress.anyLocalAddress();
            }
        } catch (Exception e) {
            in = MockInetAddress.anyLocalAddress(); // "0.0.0.0"
        }
        return in;
    }



    @Override
    public void close() {
        synchronized(closeLock) {
            if (isClosed())
                return;
            impl.close();
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        synchronized(closeLock) {
            return closed;
        }
    }

    @Override
    public DatagramChannel getChannel() {
        return null;
    }


    @Override
    public int getLocalPort() {
        if (isClosed())
            return -1;
        try {
            return getImpl().getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }


    public static synchronized void setDatagramSocketImplFactory(DatagramSocketImplFactory fac)
            throws IOException
    {
        //setting a custom factory is too risky
        throw new MockIOException("Setting of factory is not supported");
    }


    //-----------------------------------

    @Override
    public synchronized void setSoTimeout(int timeout) throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        getImpl().setOption(SocketOptions.SO_TIMEOUT, new Integer(timeout));
    }

    @Override
    public synchronized int getSoTimeout() throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        if (getImpl() == null)
            return 0;
        Object o = getImpl().getOption(SocketOptions.SO_TIMEOUT);
        /* extra type safety */
        if (o instanceof Integer) {
            return ((Integer) o).intValue();
        } else {
            return 0;
        }
    }

    @Override
    public synchronized void setSendBufferSize(int size)
            throws SocketException{
        if (!(size > 0)) {
            throw new IllegalArgumentException("negative send size");
        }
        if (isClosed())
            throw new SocketException("Socket is closed");
        getImpl().setOption(SocketOptions.SO_SNDBUF, new Integer(size));
    }

    @Override
    public synchronized int getSendBufferSize() throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        int result = 0;
        Object o = getImpl().getOption(SocketOptions.SO_SNDBUF);
        if (o instanceof Integer) {
            result = ((Integer)o).intValue();
        }
        return result;
    }

    @Override
    public synchronized void setReceiveBufferSize(int size)
            throws SocketException{
        if (size <= 0) {
            throw new IllegalArgumentException("invalid receive size");
        }
        if (isClosed())
            throw new SocketException("Socket is closed");
        getImpl().setOption(SocketOptions.SO_RCVBUF, new Integer(size));
    }

    @Override
    public synchronized int getReceiveBufferSize()
            throws SocketException{
        if (isClosed())
            throw new SocketException("Socket is closed");//TODO
        int result = 0;
        Object o = getImpl().getOption(SocketOptions.SO_RCVBUF);
        if (o instanceof Integer) {
            result = ((Integer)o).intValue();
        }
        return result;
    }

    @Override
    public synchronized void setReuseAddress(boolean on) throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed"); //TODO
        // Integer instead of Boolean for compatibility with older DatagramSocketImpl
            getImpl().setOption(SocketOptions.SO_REUSEADDR, Boolean.valueOf(on));
    }

    @Override
    public synchronized boolean getReuseAddress() throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed"); //TODO
        Object o = getImpl().getOption(SocketOptions.SO_REUSEADDR);
        return ((Boolean)o).booleanValue();
    }

    @Override
    public synchronized void setBroadcast(boolean on) throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed"); //TODO
        getImpl().setOption(SocketOptions.SO_BROADCAST, Boolean.valueOf(on));
    }

    @Override
    public synchronized boolean getBroadcast() throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed"); //TODO
        return ((Boolean)(getImpl().getOption(SocketOptions.SO_BROADCAST))).booleanValue();
    }

    @Override
    public synchronized void setTrafficClass(int tc) throws SocketException {
        if (tc < 0 || tc > 255)
            throw new MockIllegalArgumentException("tc is not in range 0 -- 255");

        if (isClosed())
            throw new SocketException("Socket is closed"); //TODO
        getImpl().setOption(SocketOptions.IP_TOS, new Integer(tc));
    }

    @Override
    public synchronized int getTrafficClass() throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed"); //TODO
        return ((Integer)(getImpl().getOption(SocketOptions.IP_TOS))).intValue();
    }


}