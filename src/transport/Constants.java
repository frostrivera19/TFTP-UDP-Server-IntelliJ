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

/**
 * This class acts only as the repository for constant values used in the
 * Server side, having no constructors or methods whatsoever.
 *
 * @author 223459 afd22@sussex.ac.uk
 * @version 1.0 %G%, %U%.
 * */
public class Constants {
    /**
     * Default byte-size of a DATA block packet content (excluding the size
     * occupied by the opcode and block number), by RFC 1350.
     * */
    protected static final int DEFAULT_DATA_SIZE = 512;
    /**
     * The hard limit placed the number of attempts to send the final DATA
     * block. It is presumed after this limit, the server has received all
     * needed data and been repeatedly attempting to send the final ACK.
     * */
    protected static final int FINAL_LOOP_LIMIT = 20;
    /**
     * Hostile feature. Sets the probability a packet will not be sent to
     * determine the robustness of this protocol. 0.0 means no packet is
     * stopped and 1.0 means all packets will not be sent.
     * */
    protected static final double LOST_PROBABILITY = 0.0;
    /**
     * The time value in milliseconds to raise the SocketTimeout exception.
     * */
    protected static final int TIMEOUT = 100;
    /**
     * The default server port where read / write requests are received.
     * Value is defined in RFC 1350.
     */
    protected static final int DEFAULT_SERVER_PORT = 69;
    /**
     * Ports 1024 - 49151 are the User Ports and are the ones to use for the
     * protocols. Ports below 1024 are the Well Known Ports and above 49151
     * are the Dynamic ports.
     * */
    protected static final int MIN_PORT = 1024;
    /**
     * Ports 1024 - 49151 are the User Ports and are the ones to use for the
     * protocols. Ports below 1024 are the Well Known Ports and above 49151
     * are the Dynamic ports.
     * */
    protected static final int MAX_PORT = 49151;
}
