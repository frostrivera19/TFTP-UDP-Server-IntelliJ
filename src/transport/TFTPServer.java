/*
 * THE TFTP PROTOCOL (Server side).
 *
 * Based on RFC 1350 at https://www.ietf.org/rfc/rfc1350.txt with some
 * simplifications. A simple file transfer protocol that reads or writes
 * files to another server working solely under octet mode, passing raw 8 bit
 * bytes of data, and implemented on top of the Internet User Datagram Protocol
 * (UDP / Datagram). Works in parallel with at least one other remote client.
 *
 * Assignment 2: Implementation of the Trivial File Transfer Protocol
 * (TFTP) of G5115 Computer Networks, University of Sussex, Spring 2020.
 * Deadline: May 08, 2020.
 *
 * @author 223459 afd22@sussex.ac.uk
 * @version 1.0 %G%, %U%.
 * */
package transport;

import java.io.IOException;
import java.net.*;
import java.util.HashMap;

/**
 * This class acts as the main body and main thread of the Server side of this
 * TFTP protocol where requests are received at the master (default) port 69.
 *
 * @author 223459 afd22@sussex.ac.uk
 * @version 1.0 %G%, %U%.
 * */
public class TFTPServer {

    /** Default size of a single DATA block content. */
    private static final int DEFAULT_DATA_SIZE
            = Constants.DEFAULT_DATA_SIZE;
    /** Default port number 69 of main server socket. */
    private static final int DEFAULT_SERVER_PORT
            = Constants.DEFAULT_SERVER_PORT;

    /** Socket with port 69 where initial read / write requests are received. */
    private static DatagramSocket defaultSocket;

    /** Main Clients currently in process by TFTPServerThread. */
    protected static HashMap<InetSocketAddress, Client> mainStatus =
            new HashMap<>();

    /** Adding or removing Clients happen here and 'mainStatus' updates
     * according to this list after every Clients in 'mainStatus' is
     * processed once (gone through one process). */
    protected static HashMap<InetSocketAddress, Client> mainStatusPending =
            new HashMap<>();

    static {
        try {
            defaultSocket = new DatagramSocket(DEFAULT_SERVER_PORT);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    /**
     * Main method.
     *
     * @param args arguments input in terminal. No arguments expected.
     * @throws IOException if an I/O error occurs.
     */
    public static void main(String[] args) throws IOException {
        System.out.println("\nServer started indefinitely...");
        System.out.println("Waiting for requests from Clients...\n");
        runTFTPServer();
    }

    /**
     * Receives write / read requests at port 69 and updates
     * 'mainStatusPending' as new requests are received. Makes the second
     * of two threads in Server side where the main thread will receive
     * requests and the second 'TFTPServerThread' processes for each received
     * requests.
     *
     * @throws IOException if an I/O error occurs.
     * */
    private static void runTFTPServer() throws IOException {
        TFTPServerThread thread = new TFTPServerThread();
        thread.start();

        System.out.println("LISTENING------------------------------");
        while (true) {
            // initialize master (default) socket and request packet
            byte[] buf = new byte[DEFAULT_DATA_SIZE + 4];
            // receive request from client
            DatagramPacket requestPacket = new DatagramPacket(buf, buf.length);

            defaultSocket.receive(requestPacket);

            InetAddress clientAddr = requestPacket.getAddress();
            int clientPort = requestPacket.getPort();
            InetSocketAddress clientSocAddr = new InetSocketAddress(
                    clientAddr, clientPort);

            if (mainStatusPending.containsKey(clientSocAddr)) {
                System.out.println("NOTE 218: Duplicate client TID "
                        + clientPort + " request rejected.\n");
                continue; // reject connection as duplicate request
            }

            System.out.println("===============RECEIVED================");

            byte[] packetContents = requestPacket.getData();
            Opcode request;
            String filename =
                    (new Client()).getFilename(packetContents);

            System.out.println("Received a request from "
                    + requestPacket.getAddress() + ", "
                    + requestPacket.getPort() + ".");
            System.out.println("File in request is " + filename + ".\n");

            if (buf[1] == Opcode.RRQ.ordinal()) {
                System.out.println("RRQ, slavePort = "
                        + TFTPServerThread.slaveSocket.getLocalPort()
                        + ".\n");
                request = Opcode.RRQ;
            } else if (buf[1] == Opcode.WRQ.ordinal()) {
                System.out.println("WRQ, slavePort = "
                        + TFTPServerThread.slaveSocket.getLocalPort()
                        + ".\n");
                request = Opcode.WRQ;
            } else {
                (new Client()).sendErrorPacket(
                        Error.ILLEGAL_OPERATION, requestPacket);
                System.out.println("NOTE 099: Request not RRQ or WRQ.\n");
                continue;
            }

            Client client = new Client(request, clientSocAddr, filename);
            mainStatusPending.put(clientSocAddr, client);

            System.out.println("TOTAL CLIENTS: " + mainStatusPending.size()
                    + ".\n");
        }
    }

    // END OF FILE
}
