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

import java.io.*;
import java.net.*;
import java.util.Map;

/**
 * This class keeps and processes the states of each Client in process.
 * TFTPServerThread will call on the this class when processing each Client.
 *
 * @author 223459 afd22@sussex.ac.uk
 * @version 1.0 %G%, %U%.
 * */
public class Client {
    /** RRQ: Block number currently in attempt to be sent to Client in a read
     *  request. Increases by one unit for each successful acknowledgement. */
    private int blockNumber = -1;
    /** RRQ: Expected acknowledgement number (block number of received ACK
     * packet) to be received in a read request. Must always be equal to
     * 'blockNumber'. */
    private int expectedAck = -1;
    /** WRQ: Expected block number to be received in a write request. */
    private int blockExpected = -1;
    /** Port number of this Client. */
    private final int clientPort;

    /** Internet address of this Client. */
    private final InetAddress clientAddr;
    /** Either a read or write request that this Client is prompting. */
    private final Opcode requestOpcode;
    /** RRQ: BufferedReader to read and send char buffers from requested file
     * in a read request. */
    private BufferedReader rdr;
    /** WRQ: Whether the final DATA packet has been successfully received. */
    private boolean writeRequestCompleted = false;

    /** Name of file in request. */
    private final String filename;
    /** RRQ: Reading file in 512 bytes and sending the read buffer in blocks. */
    private char[] readBuf = new char[DEFAULT_DATA_SIZE]; // = 512
    /** WRQ: Current content of file successfully received in a WRQ. */
    private StringBuilder fileContent;
    /** Sole slave socket of this Server. */
    private final DatagramSocket slaveSocket = TFTPServerThread.slaveSocket;

    /** Default size of a single DATA block content. */
    private static final int DEFAULT_DATA_SIZE = Constants.DEFAULT_DATA_SIZE;
    /** Limit to how many times a packet can be sent before returning. */
    private static final int LOOP_LIMIT = Constants.FINAL_LOOP_LIMIT;

    /**
     * Sole functional constructor.
     *
     * @param op either a WRQ or RRQ depending on this Client's initial prompt.
     * @param socAddr Socket Internet Address of this Client.
     * @param nameOfFile filename in request.
     * */
    public Client(Opcode op, InetSocketAddress socAddr, String nameOfFile) {
        clientPort = socAddr.getPort();
        clientAddr = socAddr.getAddress();
        requestOpcode = op;
        filename = nameOfFile;

        if (op == Opcode.RRQ) {
            blockNumber = 1;
            expectedAck = blockNumber;
        } else if (op == Opcode.WRQ) {
            blockExpected = 1;
            fileContent = new StringBuilder(DEFAULT_DATA_SIZE + 4);
        } else {
            System.out.println("ERROR 227\n");
            System.exit(-1);
        }
    }

    /**
     * Throw-away constructor solely to enable external usage of methods.
     * */
    protected Client() {
        filename = null;
        clientAddr = null;
        clientPort = -1;
        requestOpcode = Opcode.BLANK;
    }

    /**
     * RRQ: Initialises the BufferedReader to read and send file content to
     * Client in a RRQ.
     *
     * @throws IOException if an I/O error occurs.
     * */
    protected void makeBuffer() throws IOException {
        // makes a BufferedReader to read content of file
        rdr = new BufferedReader(new FileReader(filename));
    }


    /**
     * RRQ: The main read method on the Server side. Processes a single
     * request to read a file from the server. DATA packets in octet mode are
     * sent one at a time, waiting for respective acknowledgements before
     * another is sent.
     *
     * This process terminates once the final acknowledgement is received, or if
     * the final DATA packet has been sent above FINAL_LOOP_COUNT times, when
     * the server is presumed to have received the packet and kept failing to
     * send the final acknowledgement.
     *
     * Socket timeouts are initialized and handled here.
     *
     * @throws IOException if an I/O error occurs.
     * */
    protected void readFile() throws IOException {

        slaveSocket.setSoTimeout(Constants.TIMEOUT);

        // if a packet was sent but timeout before the ACK is received,
        // resend the packet once more. move on to next client at timeout or
        // receipt of the ACK
        if (blockNumber != expectedAck) {
            System.out.println("ERROR 567: blockNumber " + blockNumber
                    + " != expectedAck " + expectedAck + ".");
            rdr.close();
            slaveSocket.close();
            System.exit(-1);
        }

        int readCount;
        if ((readCount = rdr.read(readBuf, 0, readBuf.length)) > 0) {

            slaveSocket.setSoTimeout(Constants.TIMEOUT);

            int loopCount = 0;

            // sends DATA repeatedly until an ACK is received
            while (true) {
                try {
                    // sends a packet filled with 516-byte-or-less file data
                    DatagramPacket packetInLine = produceDataPacket(readBuf,
                            readCount, blockNumber);
                    packetInLine.setPort(clientPort);
                    packetInLine.setAddress(clientAddr);
                    udtSend(packetInLine, clientPort, clientAddr);

                    if (readCount < DEFAULT_DATA_SIZE) {
                        System.out.println("Last data packet "
                                + blockNumber + " sent ["
                                + slaveSocket.getLocalPort()
                                + ", " + clientPort + "].\n");
                    } else {
                        System.out.println("Data packet "
                                + blockNumber + " sent ["
                                + slaveSocket.getLocalPort()
                                + ", " + clientPort + "].\n");
                    }

                    // necessary to 'stimulate' the process, otherwise
                    // readBuf somehow won't send DO NOT DELETE
                    char randomChar = readBuf[readBuf.length / 2]; // DO NOT DEL

                    // receive ACK for sent data
                    byte[] bufAck =
                            (receiveAck(expectedAck, packetInLine)).getData();

                    int blockReceived = fromByteToInt(new byte[]{bufAck[2],
                            bufAck[3]});

                    // correct ACK received: move on to next Client
                    if (blockReceived == expectedAck) {
                        blockNumber++;
                        expectedAck++;
                        break;
                    }
                } catch (SocketTimeoutException soe) {
                    // repeat cycle until receive ACK
                    System.out.println("NOTE 868: Timeout. Resending block "
                            + blockNumber + ".\n");
                    // if final data block is consistently not acknowledged,
                    // presumed Client is terminated & all data received.
                    // Thread will be terminated.
                    // else if non-final data block is consistently not
                    // acknowledged, move on to next Client
                    if (readCount < DEFAULT_DATA_SIZE) { // final block
                        if (loopCount > LOOP_LIMIT) { // > 20
                            System.out.println("\nloopCount = " + loopCount);
                            System.out.println("\nLast block sent too "
                                    + "frequently."
                                    + " Server presumed terminated.\n");
                            break;
                        }
                    } else { // readCount == 512
                        if (loopCount > LOOP_LIMIT / 2) { // > 10
                            break;
                        }
                    }
                }
                loopCount++;
            }

            slaveSocket.setSoTimeout(0);
        }

        // if the characters read is less than 512 bytes, then either the
        // file size is a multiple of 512 bytes meaning we have to send a
        // 0-byte DATA packet or that it's the end of the file and
        // read process is completed
        if (readCount == DEFAULT_DATA_SIZE) {
            // at end of while loop, one block has been acknowledged and move
            // on to next client
            slaveSocket.setSoTimeout(0);
            return;
        } else if (readCount < DEFAULT_DATA_SIZE) {
            // if file size multiple of 512 bytes, a last packet of 0-byte data
            // size will be sent i.e. move on to next client and send the
            // last packet later. Else, file successfully sent.
            boolean fileMultipleOfBlockSize =
                    (new File(filename)).length() % DEFAULT_DATA_SIZE == 0;
            if (fileMultipleOfBlockSize) {
                sendZeroData();
            }
            // file transmission successful. terminate thread
            rdr.close();
            removeFromStatus(clientAddr, clientPort);
            slaveSocket.setSoTimeout(0);
            return;
        }
        slaveSocket.setSoTimeout(0);
    }

    /**
     * RRQ: Sends a single DATA packet with zero content to the Client in an
     * RRQ. Called when the size of the transmitted file contents is a multiple
     * of DEFAULT_DATA_SIZE as the last DATA packet to be sent.
     *
     * @throws IOException if an I/O error occurs.
     * */
    private void sendZeroData() throws IOException {
        System.out.println("NOTE 908: File size multiple of "
                + DEFAULT_DATA_SIZE + " bytes.");
        readBuf = new char[0];
        DatagramPacket packetInLine = produceDataPacket(readBuf, 0,
                blockNumber);
        packetInLine.setPort(clientPort);
        packetInLine.setAddress(clientAddr);

        // how many times server sent final data packet
        int loopCount = 0;

        // resend until ACK received
        while (true) {
            try {
                udtSend(packetInLine, clientPort, clientAddr);
                System.out.println("Last data packet with block number "
                        + blockNumber + " sent [" + slaveSocket.getLocalPort()
                        + ", " + clientPort + "].\n");
                // receive ACK for sent data
                receiveAck(expectedAck, packetInLine);
                break;
            } catch (SocketTimeoutException soe) {
                System.out.println("NOTE 304: Timeout. Resending block "
                        + blockNumber + ".");
                // if final data block is consistently not acknowledged,
                // presumed Client is terminated & all data received
                System.out.println("\nloopCount = " + loopCount);
                if (loopCount > LOOP_LIMIT) {
                    System.out.println("\nLast block sent too frequently."
                            + " Client presumed terminated.\n");
                    break;
                }
            }
            loopCount++;
        }
    }

    /**
     * Sends a single ACK with block number 0 to Client as acknowledgement of
     * WRQ request.
     *
     * @throws IOException if an I/O error occurs.
     * */
    protected void sendFirstAck() throws IOException {
        if (requestOpcode != Opcode.WRQ) {
            System.out.println("ERROR 706\n");
            System.exit(-1);
        }

        sendACK(0, clientPort, clientAddr);
    }
    /**
     * WRQ: The main write method on the Server side. Processes a single request
     * to write a file to the server. DATA packets in octet mode are received
     * and separate acknowledgements are received for each before another DATA
     * packet can be received. An acknowledgement to write was previously sent
     * to Client. Will attempt to receive DATA 1 from Client afterwards but at
     * timeout will send ACK 0 indefinitely until DATA 1 received. Returns so
     * TFTPServerThread can process next Client. At receipt of DATA, the
     * corresponding ACK will be sent and this includes DATA already
     * acknowledged (duplicates).
     *
     * Repeats receipt and return for subsequent DATA packets until the final
     * (size < DEFAULT_DATA_SIZE bytes) is received. Then, writes made
     * StringBuilder to file and returns. Returns after one ACK sent (unless if
     * it is ACK 0).
     *
     * Dallying is used where this Server Thread keeps open for 10 * TIMEOUT
     * after receiving final ACK to listen to incoming final DATA packets if
     * Client hasn't received acknowledgement. Removes this Client from
     * TFTPServer.mainStatus after timeout.
     *
     * Socket timeouts are initialized and handled here.
     *
     * @throws IOException if an I/O error occurs.
     */
    protected void receiveWrittenFile() throws IOException {

        byte[] totalBuf = new byte[DEFAULT_DATA_SIZE + 4];

        // keep receiving the duplicate of the final DATA packet if the
        // Client hasn't received the final ACK until timeout when Client
        // presumed to have received the final ACK and terminated
        if (writeRequestCompleted) {
            slaveSocket.setSoTimeout(10 * Constants.TIMEOUT);
            while (true) {
                try {
                    DatagramPacket finalDuplicate = new DatagramPacket(totalBuf,
                            totalBuf.length);
                    slaveSocket.receive(finalDuplicate);
                    if (verifySocAddr(finalDuplicate, clientAddr, clientPort)
                            && verifyPacketOpcode(finalDuplicate,
                            Opcode.DATA)) {
                        byte[] blockEncoded = {totalBuf[2], totalBuf[3]};
                        int blockReceived = fromByteToInt(blockEncoded);
                        System.out.println("NOTE 544: Received duplicate of "
                                + "final" + " DATA block " + blockReceived
                                + ".\n");
                        sendACK(blockReceived, clientPort, clientAddr);
                    }
                } catch (SocketTimeoutException soe) {
                    slaveSocket.setSoTimeout(0);
                    System.out.println("Thread terminated.\n");
                    removeFromStatus(clientAddr, clientPort);
                    return;
                }
            }
        }

        DatagramPacket received = new DatagramPacket(totalBuf, totalBuf.length);

        // if ACK 0 was received, expect to receive a DATA 1 packet. If no
        // DATA packet received from the correct source, keep receiving.
        if (blockExpected == 1) {
            slaveSocket.setSoTimeout(Constants.TIMEOUT);
            while (true) {
                try {
                    slaveSocket.receive(received);
                    if (verifySocAddr(received, clientAddr, clientPort)
                            && verifyPacketOpcode(received, Opcode.DATA)) {
                        break;
                    }
                } catch (SocketTimeoutException soe) {
                    sendFirstAck();
                }
            }
            slaveSocket.setSoTimeout(0);
        } else { // DATA 1 has been received before
            while (true) {
                // if ACK 0 successfully received and subsequent DATA packets is
                // being sent, wait indefinitely until next DATA is received
                slaveSocket.receive(received);
                // if received packet not of correct source or not DATA,
                // keep receiving
                if (verifySocAddr(received, clientAddr, clientPort)
                        && verifyPacketOpcode(received, Opcode.DATA)) {
                    break;
                }
            }
        }

        // send ACK after verifying block number; if less than
        // expected, resend  ACK; if more than expected, declare
        // missing block and exit
        byte[] blockEncoded = {totalBuf[2], totalBuf[3]};
        int blockReceived = fromByteToInt(blockEncoded);

        if (blockReceived == blockExpected) {
            sendACK(blockReceived, clientPort, clientAddr);

            // building file content
            String dataReceived = new String(received.getData(), 0,
                    received.getLength());
            fileContent.append(dataReceived.substring(4));
            blockExpected++;
        } else if (blockReceived < blockExpected) {
            System.out.println("NOTE 648: Duplicate. Packet's block "
                    + "received " + blockReceived + " < block "
                    + "expected " + blockExpected + ".");
            sendACK(blockReceived, clientPort, clientAddr);
        } else { // blockReceived > blockExpected
            System.err.println("ERROR 301: A previous block of "
                    + "data is missing.");
            System.out.println("blockReceived = " + blockReceived);
            System.out.println("blockExpected = " + blockExpected + "\n");
            slaveSocket.close();
            System.exit(-1);
        }

        // if last block, end transmission
        if (received.getLength() < DEFAULT_DATA_SIZE + 4) {
            System.out.println("Block " + blockReceived + " received "
                    + "[" + received.getPort() + ", "
                    + slaveSocket.getLocalPort() + "]."
                    + " Final ACK " + blockReceived + " sent ["
                    + slaveSocket.getLocalPort()
                    + ", " + clientPort + "].\n");

            // write file that was read
            FileWriter myWriter = new FileWriter(filename);
            myWriter.write(String.valueOf(fileContent));
            myWriter.close();
            System.out.println("File " + filename + " successfully received "
                    + "and written. Terminating thread.");
            System.out.println();
            writeRequestCompleted = true;
        } else {
            System.out.println("Block " + blockReceived + " received "
                    + "[" + received.getPort() + ", "
                    + slaveSocket.getLocalPort() + "]." + " ACK "
                    + blockReceived + " sent [" + slaveSocket.getLocalPort()
                    + ", " + clientPort + "].\n");
        }

        slaveSocket.setSoTimeout(0);
    }


    //=========================helper methods===================================

    /**
     * Returns the opcode of a TFTP operation in byte[] form, based on RFC 1350.
     *
     * @param opcode the operation in request.
     * @return opcode of operation.
     * @see Opcode
     * */
    private byte[] generateOpcode(Opcode opcode) {
        byte[] rrq = {0, (byte) Opcode.RRQ.ordinal()}; // {0, 1}
        byte[] wrq = {0, (byte) Opcode.WRQ.ordinal()}; // {0, 2}
        byte[] data = {0, (byte) Opcode.DATA.ordinal()}; // {0, 3}
        byte[] ack = {0, (byte) Opcode.ACK.ordinal()}; // {0, 4}
        byte[] error = {0, (byte) Opcode.ERROR.ordinal()}; // {0, 5}
        byte[] none = {Byte.MIN_VALUE, Byte.MIN_VALUE}; // {-128, -128}

        switch (opcode) {
            case RRQ: return rrq;
            case WRQ: return wrq;
            case DATA: return data;
            case ACK: return ack;
            case ERROR: return error;
            default:
                System.err.println("ERROR 760: Opcode not recognized.");
                slaveSocket.close();
                System.exit(-1);
                return none;
        }
    }

    /**
     * Concatenates two byte arrays in order and returns the result.
     *
     * @param array1 byte array to appear first.
     * @param array2 byte array to appear last.
     * @return concatenated result of array1 and array2 in that order.
     * */
    private byte[] combineArr(byte[] array1, byte[] array2) {
        int aLen = array1.length;
        int bLen = array2.length;
        byte[] result = new byte[aLen + bLen];

        System.arraycopy(array1, 0, result, 0, aLen);
        System.arraycopy(array2, 0, result, aLen, bLen);
        return result;
    }

    /**
     * Converts the number stored in 2-tuple byte array base-256 into the
     * base-10 integer equivalent. b must be 2-tuple as b was initially
     * constructed in fromIntToByte(int) with checking mechanisms.
     * Block Number = (b[1] + 128) + 256 * (b[0] + 128), max = 65535.
     * {-128, -128} = 0
     * {-128, 0} = 128
     * {-128, 127} = 255
     * {-127, -128} = 256
     * {127, 127} = 65535
     *
     * @param b the byte array holding the 2-tuple base-256 bytes.
     * @return b in base-10 int format.
     * */
    private int fromByteToInt(byte[] b) {
        int base = Byte.MAX_VALUE + (-1 * Byte.MIN_VALUE) + 1; // 256
        // ans = b[1] + 128 + 256 * (b[0] + 128)
        return (b[1] + (-1 * Byte.MIN_VALUE) + base * (b[0]
                + (-1 * Byte.MIN_VALUE)));
    }

    /**
     * Converts the number stored in integer base-10 format into a 2-tuple
     * Byte array, with both bytes in base-256 from min -128 to max 127.
     * Block Number = (b[1] + 128) + 256 * (b[0] + 128), max = 65535.
     * {-128, -128} = 0
     * {-128, 0} = 128
     * {-128, 127} = 255
     * {-127, -128} = 256
     * {127, 127} = 65535
     *
     * @param i number in base-10 integer format.
     * @return i in 2-tuple Byte array in base-256 (range of Byte) format.
     * Error if i is out of range (i < 0 or i > 65535).
     * */
    private byte[] fromIntToByte(int i) throws IOException {
        int base = Byte.MAX_VALUE + (-1 * Byte.MIN_VALUE) + 1; // 256
        int max = base * (base - 1) + (base - 1); // 65535

        byte zerothDigit = (byte) (i / base + Byte.MIN_VALUE); // i / 256 - 128
        byte firstDigit = (byte) ((i % base) + Byte.MIN_VALUE); // i % 256 - 128

        if (i >= 0 && i <= max) {
            return new byte[]{zerothDigit, firstDigit};
        } else {
            System.out.println("ERROR 461: Block number out of range "
                    + "[0, 65535]. ");
            System.out.println("Terminating thread.\n");
            terminatePrematurely("ERROR 461 raised.\n");
            return null;
        }
    }

    /**
     * Generates a DatagramPacket DATA packet with the given input contents of
     * the packet, with length equalling the actual used space which may be
     * 516 bytes (DEFAULT_DATA_SIZE + 4) or lower to a minimum of 4 bytes.
     *
     * @param buf character buffer of the DATA contents.
     * @param readCount number of characters in character buffer.
     * @param numberOfBlock block number of this DATA packet.
     * @return DATA packet with contents and block number.
     * */
    private DatagramPacket produceDataPacket(char[] buf, int readCount,
                                               int numberOfBlock)
            throws IOException {
        byte[] dataBuf = new byte[DEFAULT_DATA_SIZE];

        // if only reading less than 512 chars, the read contents will be
        // the last content of the file. dataBuf readjusted to reflect final
        // content length
        if (readCount < DEFAULT_DATA_SIZE) {
            dataBuf = new byte[readCount];
        }

        // copying readBuf into dataBuf
        for (int i = 0; i < readCount; i++) {
            dataBuf[i] = (byte) buf[i];
        }

        // generating opcode for DATA
        byte[] dataOpcode = generateOpcode(Opcode.DATA);

        // generating block number in byte[] form
        byte[] block = fromIntToByte(numberOfBlock);

        // dataBuf = opcode + block number + data(original dataBuf)
        assert block != null;
        byte[] opcodeAndBlock = combineArr(dataOpcode, block);
        dataBuf = combineArr(opcodeAndBlock, dataBuf);

        // produce the DatagramPacket
        return new DatagramPacket(dataBuf, dataBuf.length);
    }

    /**
     * Returns the filename from a write request (WRQ) or read request (RRQ).
     * Exits system if mode is not octet (and indirectly if the packet's
     * contents do not resemble that of a WRQ or RRQ).
     *
     * @param packetContents raw content of received WRQ or RRQ.
     * @return filename kept inside the WRQ or RRQ.
     * */
    protected String getFilename(byte[] packetContents) {

        // getting the locations of the three zero bytes
        // filename is located in index 2 : 2nd zero
        // mode is located in index (2nd zero + 1) : 3rd zero
        // per RFC: | 01/02 | Filename | 0 | Mode | 0 |
        int index2ndZero = 0;
        int index3rdZero = 0;
        int index = 0;
        for (byte b : packetContents) {
            if (index > 0) {
                if (b == 0) {
                    if (index2ndZero == 0) {
                        index2ndZero = index;
                    } else {
                        index3rdZero = index;
                        break;
                    }
                }
            }
            index++;
        }

        String nameOfFile = (new String(packetContents)).substring(2,
                index2ndZero);

        // ensuring mode is octet
        String octet =
                (new String(packetContents)).substring((index2ndZero + 1),
                        index3rdZero);
        if (!octet.equals("octet")) {
            System.out.println("ERROR 522: mode is not octet.");
            slaveSocket.close();
            System.exit(-1);
        }

        return nameOfFile;
    }

    /**
     * A blocking call to receive an acknowledgement packet (ACK). If an ACK
     * with the expected block number is not received, the packet in line is
     * resent. If non-ACK packet received, this method remains open and
     * blocking until an ACK is received.
     *
     * Timeout is not initiated or handled here.
     *
     * @param expectedAcknowNum expected block number of incoming ACK.
     * @param packetInLine packet to be sent if the expected ACK is not
     *                     received.
     * @return received acknowledgement packet.
     * @throws IOException if an I/O error occurs.
     * */
    private DatagramPacket receiveAck(int expectedAcknowNum,
                                        DatagramPacket packetInLine)
            throws IOException {

        // fixing port number of client
        // int clientPort = packetInLine.getPort();
        // InetAddress clientAddr = packetInLine.getAddress();

        // create buffer to receive ACK packet
        byte[] bufACK = new byte[4];
        DatagramPacket ackPacket = new DatagramPacket(bufACK, bufACK.length);

        // receive and verify opcode is ACK and from clientPort
        do {
            slaveSocket.receive(ackPacket);
        } while (!verifySocAddr(ackPacket, clientAddr, clientPort)
                || !verifyPacketOpcode(ackPacket, Opcode.ACK));


        // verifying expected ACK block number
        byte[] blockReceived = {bufACK[2], bufACK[3]};
        int ackReceived = fromByteToInt(blockReceived);
        if (ackReceived < expectedAcknowNum) {
            System.out.println("NOTE 002: ackReceived " + ackReceived
                    + " < expectedAcknowNum " + expectedAcknowNum
                    + ". DATA lost in network. ");
            udtSend(packetInLine, clientPort, clientAddr);
        } else if (ackReceived == expectedAcknowNum) {
            if (packetInLine.getLength() < DEFAULT_DATA_SIZE + 4) {
                System.out.println("Final data block " + ackReceived
                        + " successfully acknowledged. Terminating thread.");
            } else {
                System.out.println("Data block " + ackReceived
                        + " successfully acknowledged. Sending next block.");
            }
            System.out.println();
        } else {
            System.out.println("ERROR 004: ackReceived " + ackReceived
                    + " > expectedAcknowNum " + expectedAcknowNum + ".");
            slaveSocket.close();
            System.exit(-1);
        }
        return ackPacket;
    }

    /**
     * Sends a DatagramPacket to the given port and internet address.
     *
     * @param packet packet to be sent.
     * @param port port number of destination remote host.
     * @param addr internet address of destination remote host.
     * */
    private void udtSend(DatagramPacket packet, int port,
                           InetAddress addr) throws IOException {
        packet.setAddress(addr);
        packet.setPort(port);

        // Unnecessary random variable to invoke lost packet simulations
        double random = Math.random();

        // System.out.println(Constants.LOST_PROBABILITY);
        if (random < (1 - Constants.LOST_PROBABILITY)) {
            try {
                slaveSocket.send(packet);
            } catch (IllegalArgumentException ioe) {
                System.out.println(ioe.getMessage() + "\n");
            }
        } else {
            System.out.println("Packet made lost.");
        }
    }

    /**
     * Ensures a packet has the expected opcode. Returns true if the expected
     * opcode matches packet's opcode. False otherwise. Exits the system if a
     * packet's opcode is ERROR but the expected opcode is not ERROR, for
     * example, if a WRQ packet is responded with a FILE_NOT_FOUND error.
     *
     * @param recv packet whose opcode is to be compared.
     * @param op expected opcode.
     * @return true if packet's opcode is the expected opcode. False
     * otherwise. Exits the system if packet's opcode is ERROR when expected
     * opcode is not ERROR.
     * */
    private boolean verifyPacketOpcode(DatagramPacket recv, Opcode op)
            throws IOException {
        byte[] recvBuf = recv.getData();
        boolean isRRQ =
                ((generateOpcode(Opcode.RRQ)[1]) == recvBuf[1]);
        boolean isWRQ =
                ((generateOpcode(Opcode.WRQ)[1]) == recvBuf[1]);
        boolean isData =
                ((generateOpcode(Opcode.DATA)[1]) == recvBuf[1]);
        boolean isError =
                ((generateOpcode(Opcode.ERROR)[1]) == recvBuf[1]);
        boolean isAck =
                ((generateOpcode(Opcode.ACK)[1]) == recvBuf[1]);
        switch (op) {
            case RRQ:
                if (isRRQ) {
                    System.out.println("Request received: RRQ from "
                            + "addr " + recv.getAddress() + " port "
                            + recv.getPort() + ".");
                    return true;
                }
                break;
            case WRQ:
                if (isWRQ) {
                    System.out.println("Request received: WRQ from "
                            + "addr " + recv.getAddress() + " port "
                            + recv.getPort() + ".");
                    return true;
                }
                break;
            case DATA:
                if (isData) {
                    return true;
                }
                break;
            case ERROR:
                if (isError) {
                    int errorCode = recvBuf[3];
                    String dataReceived = new String(recvBuf);
                    String errMsg = dataReceived.substring(4,
                            recv.getLength() - 1);
                    System.out.println("ERROR 454: Expected error code "
                            + errorCode + " with message: " + errMsg);
                    System.out.println();
                    return true;
                }
                break;
            case ACK:
                if (isAck) {
                    return true;
                }
                break;
            default:
                System.out.println("ERROR 631: Unknown opcode of data "
                        + "received: " + recvBuf[1]);
                System.out.println();
                System.out.println("Terminating server...");
                slaveSocket.close();
                System.exit(-1);
        }
        if (isError) {
            int errorCode = recvBuf[3];
            String dataReceived = new String(recvBuf);
            try {
                String errMsg = dataReceived.substring(4, recv.getLength() - 1);
                System.out.println("ERROR 978: Unexpected error code 0"
                        + errorCode + " with message: " + errMsg + ".\n");
            } catch (IndexOutOfBoundsException ioe) {
                System.out.println(ioe.getMessage() + "\n");
            }
            if (errorCode != Error.UNKNOWN_TID.ordinal()) {
                System.out.println("Terminating thread.\n");
                terminatePrematurely("ERROR 977 raised.\n");
            }
        }
        return false;
    }

    /**
     * Sends an acknowledgement packet (ACK) with the specified block number to
     * the server.
     *
     * @param block block number of DATA packet to be acknowledged.
     * @param port port number of client.
     * @param addr internet address of client.
     * @throws IOException if an I/O error occurs.
     * */
    private void sendACK(int block, int port, InetAddress addr)
            throws IOException {
        byte[] blockInBytes = fromIntToByte(block);
        assert blockInBytes != null;
        byte[] packetContents = combineArr(generateOpcode(Opcode.ACK),
                blockInBytes);
        DatagramPacket ackToSend = new DatagramPacket(packetContents,
                packetContents.length);
        udtSend(ackToSend, port, addr);
    }

    /**
     * Sends an ERROR packet with a FILE_NOT_FOUND error message to the
     * remote Client. Called when a read request (RRQ) is received and the
     * file requested is not found. No acknowledgements expected but this
     * method is repeatedly called by the caller as long as the read
     * request is received again.
     *
     * @param received received read request packet.
     * @throws IOException if an I/O error occurs.
     * */
    protected void sendFileNotFoundError(DatagramPacket received)
            throws IOException {
        sendErrorPacket(Error.FILE_NOT_FOUND, received);
    }

    /**
     * Sends an ERROR packet to the sender upon receipt of unexpected packet
     * or a request which cannot be fulfilled including because of a
     * file-not-found error. Terminates (exits) client where necessary. Error
     * codes are based on RFC 1350.
     *
     * @param op error code of this error.
     * @param received packet received which raised this error.
     * @throws IOException if an I/O error occurs.
     * @see Error
     * */
    protected void sendErrorPacket(Error op, DatagramPacket received)
            throws IOException {
        byte[] opcode = generateOpcode(Opcode.ERROR);
        byte[] errCode = {Byte.MIN_VALUE, Byte.MIN_VALUE};
        byte[] errMsg;
        byte[] zero = {0};
        String message;
        boolean terminate = false;

        switch (op) {
            case NOT_DEFINED:
                // {0, 0}
                errCode = new byte[]{0, (byte) Error.NOT_DEFINED.ordinal()};
                message = "Not defined.";
                terminate = true;
                break;
            case FILE_NOT_FOUND:
                // {0, 1}
                errCode = new byte[]{0, (byte) Error.FILE_NOT_FOUND.ordinal()};
                String nameOfFile = getFilename(received.getData());
                message = "File " + nameOfFile + " not found.";
                terminate = true;
                break;
            case ACCESS_VIOLATION:
                // {0, 2}
                errCode = new byte[]{0, (byte) Error.ACCESS_VIOLATION
                        .ordinal()};
                message = "Access Violation.";
                terminate = true;
                break;
            case DISK_FULL:
                // {0, 3}
                errCode = new byte[]{0, (byte) Error.DISK_FULL.ordinal()};
                message = "Disk full or allocation exceeded.";
                terminate = true;
                break;
            case ILLEGAL_OPERATION:
                // {0, 4}
                errCode = new byte[]{0, (byte) Error.ILLEGAL_OPERATION
                        .ordinal()};
                message = "Illegal TFTP operation. Expecting a Write Request "
                        + "or Read Request.";
                terminate = true;
                break;
            case UNKNOWN_TID:
                // {0, 5}
                errCode = new byte[]{0, (byte) Error.UNKNOWN_TID.ordinal()};
                message = "Unknown Transfer ID " + received.getPort()
                        + ". This connection is already used.";
                // terminate = false
                break;
            case FILE_ALREADY_EXISTS:
                // {0, 6}
                errCode = new byte[]{0, (byte) Error.FILE_ALREADY_EXISTS
                        .ordinal()};
                message = "File already exists.";
                terminate = true;
                break;
            case NO_SUCH_USER:
                // {0, 7}
                errCode = new byte[]{0, (byte) Error.NO_SUCH_USER.ordinal()};
                message = "Mo such user.";
                terminate = true;
                break;
            default:
                message = "Not defined.";
                System.out.println("ERROR 993: Unknown Error Opcode.");
                terminate = true;
        }

        errMsg = (message).getBytes();
        System.out.println("ERROR 0" + errCode[1] + ": " + message + "\n");

        byte[] first = combineArr(opcode, errCode);
        byte[] second = combineArr(first, errMsg);
        byte[] third = combineArr(second, zero);

        DatagramPacket packet = new DatagramPacket(third, third.length);
        udtSend(packet, received.getPort(), received.getAddress());

        if (terminate) {
            System.out.println("Terminating thread.\n");
            removeFromStatus(clientAddr, clientPort);
        }
    }

    /**
     * Verifies the source port number of a received packet is equal to the
     * expected port number.
     *
     * @param received received packet.
     * @param port expected port number.
     * @return true if received's port is equal to expected port. False
     * otherwise.
     * */
    private boolean verifyPort(DatagramPacket received, int port) {
        if (received.getPort() != port) {
            //System.out.println("received's port " + received.getPort() + "
            // != "
             //       + " clientPort " + port + ". slavePort = "
              //      + slaveSocket.getLocalPort() );
            // sendConnectionUsedError(received);
            return false;
        } else {
            return true;
        }
    }

    /** Returns true if source address and port of received packet is equal
     * to the address and port input. False otherwise.
     *
     * @param received received packet to be verified.
     * @param addr Internet address of source Client.
     * @param port port number of source Client.
     * @return true if port and address input matches that of the packet's.
     * False otherwise.
     * */
    private boolean verifySocAddr(DatagramPacket received, InetAddress addr,
                                  int port) {

        if (!verifyPort(received, port)) {
            return false;
        }

        return received.getAddress().equals(addr);
    }

    /**
     * Removes client from the mainStatus in TFTPServer after all processes
     * with client is done and connection closes with that client.
     *
     * @param addr internet address of client to be removed.
     * @param port port of client to be removed
     * */
    private void removeFromStatus(InetAddress addr, int port) {
        InetSocketAddress toRemove = null;
        for (Map.Entry<InetSocketAddress, Client> i
                : TFTPServer.mainStatusPending.entrySet()) {
            InetSocketAddress current = i.getKey();
            if (current.equals(new InetSocketAddress(addr, port))) {
                toRemove = current;
            }
        }

        if (toRemove != null) {
            TFTPServer.mainStatusPending.remove(toRemove);
        }
    }

    /**
     * Terminates this Client (remove from TFTPServer.mainStatus). Called if
     * a terminating error is raised. Does not close slaveSocket.
     *
     * @param errMsg error message raised.
     *
     * @throws IOException if an I/O error occurs.
     * */
    private void terminatePrematurely(String errMsg) throws IOException {
        removeFromStatus(clientAddr, clientPort);
        throw new IOException(errMsg);
    }

    /**
     * Generates either a write request (WRQ) packet or read request (RRQ)
     * packet filled with a nameOfFile. Packet is without a predefined address
     * or port number.
     *
     * @param opcode opcode of operation, whether WRQ or RRQ.
     * @param nameOfFile name of file to be read or written.
     * @param addr Internet address of source Client of this request.
     * @param port port number of source Client of this request.
     * @return WRQ or RRQ for file in question without address or port number.
     * */
    protected DatagramPacket generateRequestPacket(Opcode opcode,
                                                 String nameOfFile,
                                                 InetAddress addr, int port) {
        byte[] opcodeInByte = {Byte.MIN_VALUE, Byte.MIN_VALUE};
        if (opcode == Opcode.RRQ) {
            // getting opcode
            opcodeInByte = generateOpcode(Opcode.RRQ);
        } else if (opcode == Opcode.WRQ) {
            // getting opcode
            opcodeInByte = generateOpcode(Opcode.WRQ);
        } else {
            System.out.println("ERROR 005: Opcode is not RRQ or WRQ.");
            slaveSocket.close();
            System.exit(-1);
        }

        // getting mode, here remaining as octet
        String mode = "octet";

        // producing RRQ
        byte[] firstContentInBytes = combineArr(opcodeInByte,
                nameOfFile.getBytes());
        byte[] zero = {0};
        byte[] secondContentInBytes = combineArr(firstContentInBytes, zero);
        byte[] thirdContentInBytes = combineArr(secondContentInBytes,
                mode.getBytes());
        byte[] finalContentInBytes = combineArr(thirdContentInBytes, zero);
        return new DatagramPacket(finalContentInBytes,
                finalContentInBytes.length, addr, port);
    }



    // getters and setters------------------------------------------------------

    /**
     * RRQ: Returns current block number being sent in an RRQ.
     * @return current block number being sent.
     * */
    public int getBlockNumber() {
        return blockNumber;
    }
    /**
     * Returns the initial request of the Client, whether a WRQ or RRQ.
     * @return main request of this Client.
     * */
    public Opcode getRequestOpcode() {
        return requestOpcode;
    }
    /**
     * Returns filename of file in request.
     * @return name of file in request.
     * */
    public String getFilename() {
        return filename;
    }
    /**
     * Returns port number of this Client.
     * @return port of Client.
     * */
    public int getClientPort() {
        return clientPort;
    }
    /**
     * Returns internet address of this Client.
     * @return internet address of Client.
     * */
    public InetAddress getClientAddr() {
        return clientAddr;
    }
    /**
     * WRQ: Returns expected DATA block number to be received in a WRQ.
     * @return expected DATA block to be received.
     * */
    public int getBlockExpected() {
        return blockExpected;
    }
    // END OF FILE
}
