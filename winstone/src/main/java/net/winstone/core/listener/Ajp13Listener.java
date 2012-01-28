/*
 * Copyright 2003-2006 Rick Knowles <winstone-devel at lists sourceforge net>
 * Distributed under the terms of either:
 * - the common development and distribution license (CDDL), v1.0; or
 * - the GNU Lesser General Public License, v2.1 or later
 */
package net.winstone.core.listener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import net.winstone.WinstoneException;
import net.winstone.core.HostGroup;
import net.winstone.core.ObjectPool;
import net.winstone.core.WinstoneInputStream;
import net.winstone.core.WinstoneOutputStream;
import net.winstone.core.WinstoneRequest;
import net.winstone.core.WinstoneResponse;
import net.winstone.core.authentication.AuthenticationPrincipal;
import net.winstone.util.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the main listener daemon thread. This is the class that gets launched by the command line, and owns the server socket, etc.
 * 
 * @author mailto: <a href="rick_knowles@hotmail.com">Rick Knowles</a>
 * @version $Id: Ajp13Listener.java,v 1.13 2008/09/05 02:39:47 rickknowles Exp $
 */
public class Ajp13Listener implements Listener, Runnable {

    protected static Logger logger = LoggerFactory.getLogger(Ajp13Listener.class);
    private final static int LISTENER_TIMEOUT = 5000; // every 5s reset the listener socket
    private final static int DEFAULT_PORT = 8009;
    private final static int CONNECTION_TIMEOUT = 60000;
    private final static int BACKLOG_COUNT = 1000;
    // private final static int KEEP_ALIVE_TIMEOUT = -1;
    // private final static int KEEP_ALIVE_SLEEP = 50;
    // private final static int KEEP_ALIVE_SLEEP_MAX = 500;
    private final static String TEMPORARY_URL_STASH = "winstone.ajp13.TemporaryURLAttribute";
    private final HostGroup hostGroup;
    private final ObjectPool objectPool;
    private final int listenPort;
    private boolean interrupted;
    private final String listenAddress;

    /**
     * Constructor
     */
    public Ajp13Listener(final Map<String, String> args, final ObjectPool objectPool, final HostGroup hostGroup) {
        // Load resources
        this.hostGroup = hostGroup;
        this.objectPool = objectPool;

        this.listenPort = Integer.parseInt(StringUtils.stringArg(args, "ajp13Port", "" + DEFAULT_PORT));
        this.listenAddress = StringUtils.stringArg(args, "ajp13ListenAddress", null);
    }

    @Override
    public boolean start() {
        if (this.listenPort < 0) {
            return false;
        } else {
            this.interrupted = false;
            Thread thread = new Thread(this, StringUtils.replaceToken("ConnectorThread:[{}-[#1]]", "ajp13", Integer.toString(this.listenPort)));
            thread.setDaemon(true);
            thread.start();
            return true;
        }
    }

    /**
     * The main run method. This handles the normal thread processing.
     */
    @Override
    public void run() {
        try {
            ServerSocket ss = this.listenAddress == null ? new ServerSocket(this.listenPort, BACKLOG_COUNT) : new ServerSocket(this.listenPort, BACKLOG_COUNT, InetAddress.getByName(this.listenAddress));
            ss.setSoTimeout(LISTENER_TIMEOUT);
            logger.info("AJP13 Listener started: port={}", this.listenPort + "");

            // Enter the main loop
            while (!interrupted) {
                // Get the listener
                Socket s = null;
                try {
                    s = ss.accept();
                } catch (java.io.InterruptedIOException err) {
                    s = null;
                }

                // if we actually got a socket, process it. Otherwise go around
                // again
                if (s != null) {
                    this.objectPool.handleRequest(s, this);
                }
            }

            // Close server socket
            ss.close();
        } catch (Throwable err) {
            logger.error("Error during AJP13 listener init or shutdown", err);
        }

        logger.info("AJP13 Listener shutdown successfully");
    }

    /**
     * Interrupts the listener thread. This will trigger a listener shutdown once the so timeout has passed.
     */
    @Override
    public void destroy() {
        this.interrupted = true;
    }

    /**
     * Called by the request handler thread, because it needs specific setup code for this connection's protocol (ie construction of
     * request/response objects, in/out streams, etc). This implementation parses incoming AJP13 packets, and builds an outputstream that is
     * capable of writing back the response in AJP13 packets.
     */
    @Override
    public void allocateRequestResponse(Socket socket, InputStream inSocket, OutputStream outSocket, RequestHandlerThread handler, boolean iAmFirst) throws SocketException, IOException {
        WinstoneRequest request = this.objectPool.getRequestFromPool();
        WinstoneResponse response = this.objectPool.getResponseFromPool();
        response.setRequest(request);
        request.setHostGroup(this.hostGroup);
        // rsp.updateContentTypeHeader("text/html");
        handler.setRequest(request);
        handler.setResponse(response);

        if (iAmFirst) {
            socket.setSoTimeout(CONNECTION_TIMEOUT);
        }
        // Dead Code
        // else {
        // deallocateRequestResponse(handler, request, response, null, null);
        // }
        // else
        // socket.setSoTimeout(KEEP_ALIVE_TIMEOUT);
        Ajp13IncomingPacket headers = null;
        try {
            headers = new Ajp13IncomingPacket(inSocket, handler);
        } catch (InterruptedIOException err) {
            deallocateRequestResponse(handler, request, response, null, null);
            // keep alive timeout ? ignore if not first
            if (iAmFirst) {
                throw err;
            } else {
                return;
            }
        } catch (WinstoneException err) {
            deallocateRequestResponse(handler, request, response, null, null);
            throw err;
        } finally {
            try {
                socket.setSoTimeout(CONNECTION_TIMEOUT);
            } catch (Throwable err) {
            }
        }

        if (headers.getPacketLength() > 0) {
            headers.parsePacket("8859_1");
            parseSocketInfo(headers, request);
            request.parseHeaders(Arrays.asList(headers.getHeaders()));
            String servletURI = parseURILine(headers, request, response);
            request.setAttribute(TEMPORARY_URL_STASH, servletURI);

            // If content-length present and non-zero, download the other
            // packets
            WinstoneInputStream inData = null;
            int contentLength = request.getContentLength();
            if (contentLength > 0) {
                byte bodyContent[] = new byte[contentLength];
                int position = 0;
                while (position < contentLength) {
                    outSocket.write(getBodyRequestPacket(Math.min(contentLength - position, 8184)));
                    position = getBodyResponsePacket(inSocket, bodyContent, position);
                    logger.debug("Read {}/{} bytes from request body", "" + position, "" + contentLength);

                }
                inData = new WinstoneInputStream(bodyContent);
                inData.setContentLength(contentLength);
            } else {
                inData = new WinstoneInputStream(new byte[0]);
            }
            request.setInputStream(inData);

            // Build input/output streams, plus request/response
            WinstoneOutputStream outData = new Ajp13OutputStream(socket.getOutputStream(), "8859_1");
            outData.setResponse(response);
            response.setOutputStream(outData);

            // Set the handler's member variables so it can execute the servlet
            handler.setInStream(inData);
            handler.setOutStream(outData);
        } else {
            // deallocates response/request if the headers packet length is 0
            deallocateRequestResponse(handler, request, response, null, null);
        }
    }

    /**
     * Called by the request handler thread, because it needs specific shutdown code for this connection's protocol (ie releasing
     * input/output streams, etc).
     */
    @Override
    public void deallocateRequestResponse(RequestHandlerThread handler, WinstoneRequest request, WinstoneResponse response, WinstoneInputStream inData, WinstoneOutputStream outData) throws IOException {
        handler.setInStream(null);
        handler.setOutStream(null);
        handler.setRequest(null);
        handler.setResponse(null);
        if (request != null) {
            this.objectPool.releaseRequestToPool(request);
        }
        if (response != null) {
            this.objectPool.releaseResponseToPool(response);
        }
    }

    /**
     * This is kind of a hack, since we have already parsed the uri to get the input stream. Just pass back the request uri
     */
    @Override
    public String parseURI(RequestHandlerThread handler, WinstoneRequest req, WinstoneResponse rsp, WinstoneInputStream inData, Socket socket, boolean iAmFirst) throws IOException {
        String uri = (String) req.getAttribute(TEMPORARY_URL_STASH);
        req.removeAttribute(TEMPORARY_URL_STASH);
        return uri;
    }

    /**
     * Called by the request handler thread, because it needs specific shutdown code for this connection's protocol if the keep-alive period
     * expires (ie closing sockets, etc). This implementation simply shuts down the socket and streams.
     */
    @Override
    public void releaseSocket(Socket socket, InputStream inSocket, OutputStream outSocket) throws IOException {
        // Logger.log(Logger.FULL_DEBUG, "Releasing socket: " +
        // Thread.currentThread().getName());
        inSocket.close();
        outSocket.close();
        socket.close();
    }

    /**
     * Extract the header details relating to socket stuff from the ajp13 header packet
     */
    private void parseSocketInfo(Ajp13IncomingPacket headers, WinstoneRequest req) {
        req.setServerPort(headers.getServerPort());
        req.setRemoteIP(headers.getRemoteAddress());
        req.setServerName(headers.getServerName());
        req.setLocalPort(headers.getServerPort());
        req.setLocalAddr(headers.getServerName());
        req.setRemoteIP(headers.getRemoteAddress());
        if ((headers.getRemoteHost() != null) && !headers.getRemoteHost().equals("")) {
            req.setRemoteName(headers.getRemoteHost());
        } else {
            req.setRemoteName(headers.getRemoteAddress());
        }
        req.setScheme(headers.isSSL() ? "https" : "http");
        req.setIsSecure(headers.isSSL());
    }

    /**
     * Extract the header details relating to protocol, uri, etc from the ajp13 header packet
     */
    private String parseURILine(Ajp13IncomingPacket headers, WinstoneRequest req, WinstoneResponse rsp) throws UnsupportedEncodingException {
        req.setMethod(headers.getMethod());
        req.setProtocol(headers.getProtocol());
        rsp.setProtocol(headers.getProtocol());
        rsp.extractRequestKeepAliveHeader(req);
        // req.setServletPath(headers.getURI());
        // req.setRequestURI(headers.getURI());

        // Get query string if supplied
        for (Iterator<String> i = headers.getAttributes().keySet().iterator(); i.hasNext();) {
            String attName = (String) i.next();
            if (attName.equals("query_string")) {
                String qs = (String) headers.getAttributes().get("query_string");
                req.setQueryString(qs);
                // req.getParameters().putAll(WinstoneRequest.extractParameters(qs,
                // req.getEncoding(), mainResources));
                // req.setRequestURI(headers.getURI() + "?" + qs);
            } else if (attName.equals("ssl_cert")) {
                String certValue = (String) headers.getAttributes().get("ssl_cert");
                InputStream certStream = new ByteArrayInputStream(certValue.getBytes("8859_1"));
                X509Certificate certificateArray[] = new X509Certificate[1];
                try {
                    certificateArray[0] = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(certStream);
                } catch (CertificateException err) {
                    logger.debug("Skipping invalid SSL certificate: {}", certValue);
                }
                req.setAttribute("javax.servlet.request.X509Certificate", certificateArray);
                req.setIsSecure(true);
            } else if (attName.equals("ssl_cipher")) {
                String cipher = (String) headers.getAttributes().get("ssl_cipher");
                req.setAttribute("javax.servlet.request.cipher_suite", cipher);
                req.setAttribute("javax.servlet.request.key_size", getKeySize(cipher));
                req.setIsSecure(true);
            } else if (attName.equals("ssl_session")) {
                req.setAttribute("javax.servlet.request.ssl_session", headers.getAttributes().get("ssl_session"));
                req.setIsSecure(true);
            } else if (attName.equals("remote_user")) {
                String username = (String) headers.getAttributes().get(attName);
                String authType = (String) headers.getAttributes().get("auth_type");
                AuthenticationPrincipal principal = new AuthenticationPrincipal(username, null, null);
                principal.setAuthType(authType);
                req.setRemoteUser(principal);
            } else {
                logger.debug("Unknown request attribute ignored: {}={}", attName, "" + headers.getAttributes().get(attName));
            }
        }
        return headers.getURI();

    }

    private Integer getKeySize(String cipherSuite) {
        if (cipherSuite.indexOf("_WITH_NULL_") != -1) {
            return new Integer(0);
        } else if (cipherSuite.indexOf("_WITH_IDEA_CBC_") != -1) {
            return new Integer(128);
        } else if (cipherSuite.indexOf("_WITH_RC2_CBC_40_") != -1) {
            return new Integer(40);
        } else if (cipherSuite.indexOf("_WITH_RC4_40_") != -1) {
            return new Integer(40);
        } else if (cipherSuite.indexOf("_WITH_RC4_128_") != -1) {
            return new Integer(128);
        } else if (cipherSuite.indexOf("_WITH_DES40_CBC_") != -1) {
            return new Integer(40);
        } else if (cipherSuite.indexOf("_WITH_DES_CBC_") != -1) {
            return new Integer(56);
        } else if (cipherSuite.indexOf("_WITH_3DES_EDE_CBC_") != -1) {
            return new Integer(168);
        } else {
            return null;
        }
    }

    /**
     * Tries to wait for extra requests on the same socket. If any are found before the timeout expires, it exits with a true, indicating a
     * new request is waiting. If the timeout expires, return a false, instructing the handler thread to begin shutting down the socket and
     * relase itself.
     */
    @Override
    public boolean processKeepAlive(WinstoneRequest request, WinstoneResponse response, InputStream inSocket) throws IOException, InterruptedException {
        return true;
    }

    /**
     * Build the packet needed for asking for a body chunk
     */
    private byte[] getBodyRequestPacket(int desiredPacketLength) {
        byte getBodyRequestPacket[] = new byte[]{
            0x41, 0x42, 0x00, 0x03, 0x06, 0x00, 0x00
        };
        Ajp13OutputStream.setIntBlock(desiredPacketLength, getBodyRequestPacket, 5);
        return getBodyRequestPacket;
    }

    /**
     * Process the server response to a get_body_chunk request. This loads the packet from the stream, and unpacks it into the buffer at the
     * right place.
     */
    private int getBodyResponsePacket(InputStream in, byte buffer[], int offset) throws IOException {
        // Get the incoming packet flag
        byte headerBuffer[] = new byte[4];
        int headerBytesRead = in.read(headerBuffer);
        if (headerBytesRead != 4) {
            throw new WinstoneException("Invalid AJP header");
        } else if ((headerBuffer[0] != 0x12) || (headerBuffer[1] != 0x34)) {
            throw new WinstoneException("Invalid AJP header");
        }

        // Read in the whole packet
        int packetLength = ((headerBuffer[2] & 0xFF) << 8) + (headerBuffer[3] & 0xFF);
        if (packetLength == 0) {
            return offset;
        }

        // Look for packet length
        byte bodyLengthBuffer[] = new byte[2];
        in.read(bodyLengthBuffer);
        int bodyLength = ((bodyLengthBuffer[0] & 0xFF) << 8) + (bodyLengthBuffer[1] & 0xFF);
        int packetBytesRead = in.read(buffer, offset, bodyLength);

        if (packetBytesRead < bodyLength) {
            throw new WinstoneException("Short AJP packet");
        } else {
            return packetBytesRead + offset;
        }
    }
    //
    // /**
    // * Useful method for dumping out the contents of a packet in hex form
    // */
    // public static void packetDump(byte packetBytes[], int packetLength) {
    // String dump = "";
    // for (int n = 0; n < packetLength; n+=16) {
    // String line = Integer.toHexString((n >> 4) & 0xF) + "0:";
    // for (int j = 0; j < Math.min(packetLength - n, 16); j++)
    // line = line + " " + ((packetBytes[n + j] & 0xFF) < 16 ? "0" : "") +
    // Integer.toHexString(packetBytes[n + j] & 0xFF);
    //       
    // line = line + "    ";
    // for (int j = 0; j < Math.min(packetLength - n, 16); j++) {
    // byte me = (byte) (packetBytes[n + j] & 0xFF);
    // line = line + (((me > 32) && (me < 123)) ? (char) me : '.');
    // }
    // dump = dump + line + "\r\n";
    // }
    // System.out.println(dump);
    // }
}
