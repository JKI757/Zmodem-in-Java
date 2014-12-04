package ru.sir.ymodem;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * This is core Modem class supporting XModem (and some extensions XModem-1K, XModem-CRC), and YModem.<br/>
 * YModem support is limited (currently block 0 is ignored).<br/>
 * <br/>
 * Created by Anton Sirotinkin (aesirot@mail.ru), Moscow 2014 <br/>
 * I hope you will find this program useful.<br/>
 * You are free to use/modify the code for any purpose, but please leave a reference to me.<br/>
 */
class Modem {

    /* Protocol characters used */
    protected static final byte SOH = 0x01; /* Start Of Header */
    protected static final byte STX = 0x02; /* Start Of Text (used like SOH but means 1024 block size) */
    protected static final byte EOT = 0x04; /* End Of Transmission */
    protected static final byte ACK = 0x06; /* ACKnowlege */
    protected static final byte NAK = 0x15; /* Negative AcKnowlege */
    protected static final byte CAN = 0x18; /* CANcel character */

    protected static final byte CPMEOF = 0x1A;
    protected static final byte ST_C = 'C';

    protected static final int MAXERRORS = 10;

    private final InputStream inputStream;
    private final OutputStream outputStream;

    private final byte[] shortBlockBuffer;
    private final byte[] longBlockBuffer;

    /**
     * Constructor
     *
     * @param inputStream  stream for reading received data from other side
     * @param outputStream stream for writing data to other side
     */
    protected Modem(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        shortBlockBuffer = new byte[128];
        longBlockBuffer = new byte[1024];
    }


    /**
     * Wait for receiver request for transmission
     *
     * @param timer
     * @return TRUE if receiver requested CRC-16 checksum, FALSE if 8bit checksum
     * @throws java.io.IOException
     */
    protected boolean waitReceiverRequest(Timer timer) throws IOException {
        int character;
        while (true) {
            try {
                character = readByte(timer);
                if (character == NAK)
                    return false;
                if (character == ST_C) {
                    return true;
                }
            } catch (TimeoutException e) {
                throw new IOException("Timeout waiting for receiver");
            }
        }
    }


    /**
     * Send a file. <br/>
     * <p>
     * This method support correct thread interruption, when thread is interrupted "cancel of transmission" will be send.
     * So you can move long transmission to other thread and interrupt it according to your algorithm.
     *
     * @param file
     * @param useBlock1K
     * @throws java.io.IOException
     */
    protected void send(Path file, boolean useBlock1K) throws IOException {
        //open file
        try (DataInputStream dataStream = new DataInputStream(Files.newInputStream(file))) {
            int blockNumber = 1;
            Timer timer = new Timer(60_000).start();

            boolean useCRC16 = waitReceiverRequest(timer);
            CRC crc;
            if (useCRC16)
                crc = new CRC16();
            else
                crc = new CRC8();

            byte[] block;
            int dataLength;
            if (useBlock1K)
                block = new byte[1024];
            else
                block = new byte[128];
            while ((dataLength = dataStream.read(block)) != -1) {
                blockNumber = sendBlock(blockNumber, block, dataLength, crc);
            }

            sendEOT();
        }
    }

    protected void sendEOT() throws IOException {
        int errorCount = 0;
        Timer timer = new Timer(1_000);
        int character;
        while (errorCount<10) {
            sendByte(EOT);
            try {
                character = readByte(timer.start());

                if (character == ACK) {
                    return;
                } else if (character == CAN) {
                    throw new IOException("Transmission terminated");
                }
            } catch (TimeoutException ignored) {
            }
            errorCount++;
        }
    }

    protected int sendBlock(int blockNumber, byte[] block, int dataLength, CRC crc) throws IOException {
        int errorCount;
        int character;
        Timer timer = new Timer(10_000);

        if (dataLength < block.length) {
            block[dataLength] = CPMEOF;
        }
        errorCount = 0;

        Lp:
        while (errorCount < MAXERRORS) {
            timer.start();

            if (block.length == 1024)
                outputStream.write(STX);
            else //128
                outputStream.write(SOH);
            outputStream.write(blockNumber);
            outputStream.write(~blockNumber);

            outputStream.write(block);

            crc.writeCRC(outputStream, block);
            outputStream.flush();

            while (true) {
                try {
                    character = readByte(timer);
                    if (character == ACK) {
                        blockNumber++;
                        break Lp;
                    } else if (character == NAK) {
                        errorCount++;
                        break;
                    } else if (character == CAN) {
                        throw new IOException("Transmission terminated");
                    }
                } catch (TimeoutException e) {
                    errorCount++;
                    break;
                }
            }

        }
        if (errorCount == MAXERRORS) {
            throw new IOException("Too many errors caught, abandoning transfer");
        }
        return blockNumber;
    }

    /**
     * Receive file <br/>
     * <p>
     * This method support correct thread interruption, when thread is interrupted "cancel of transmission" will be send.
     * So you can move long transmission to other thread and interrupt it according to your algorithm.
     *
     * @param file file path for storing
     * @throws java.io.IOException
     */
    protected void receive(Path file, boolean useCRC16) throws IOException {
        try (DataOutputStream dataOutput = new DataOutputStream(Files.newOutputStream(file))) {
            int available;
            // clean input stream
            if ((available = inputStream.available()) > 0) {
                inputStream.skip(available);
            }

            int character = requestTransmissionStart(useCRC16);

            CRC crc;
            if (useCRC16)
                crc = new CRC16();
            else
                crc = new CRC8();


            processDataBlocks(crc, 1, character, dataOutput);
        }
    }

    protected void processDataBlocks(CRC crc, int blockNumber, int character, DataOutputStream dataOutput) throws IOException {
        // read blocks until EOT
        boolean result = false;
        boolean shortBlock;
        byte[] block;
        while (true) {
            int errorCount = 0;
            if (character == EOT) {
                // end of transmission
                sendByte(ACK);
                return;
            }

            //read and process block
            shortBlock = (character == SOH);
            try {
                block = readBlock(blockNumber, shortBlock, crc);
                dataOutput.write(block);
                blockNumber++;
                errorCount = 0;
                result = true;
                sendByte(ACK);
            } catch (TimeoutException | InvalidBlockException e) {
                errorCount++;
                if (errorCount == MAXERRORS) {
                    interruptTransmission();
                    throw new IOException("Transmission aborted, error count exceeded max");
                }
                sendByte(NAK);
                result = false;
            } catch (RepeatedBlockException e) {
                //thats ok, accept and wait for next block
                sendByte(ACK);
            } catch (SynchronizationLostException e) {
                //fatal transmission error
                interruptTransmission();
                throw new IOException("Fatal transmission error", e);
            }

            //wait for next block
            character = readNextBlockStart(result);
        }
    }

    protected void sendByte(byte b) throws IOException {
        outputStream.write(b);
        outputStream.flush();
    }

    /**
     * Request transmission start and return first byte of "first" block from sender (block 1 for XModem, block 0 for YModem)
     *
     * @param useCRC16
     * @return
     * @throws java.io.IOException
     */
    protected int requestTransmissionStart(boolean useCRC16) throws IOException {
        int character;
        int errorCount = 0;
        byte requestStartByte;
        if (!useCRC16) {
            requestStartByte = NAK;
        } else {
            requestStartByte = ST_C;
        }
        // request transmission start (will be repeated after 10 second timeout for 10 times)
        outputStream.write(requestStartByte);
        outputStream.flush();
        Timer timer = new Timer(3_000).start();
        // wait for first block start
        while (true) {
            while (inputStream.available() > 0) {
                character = inputStream.read();
                if (character == SOH || character == STX) {
                    //first block!
                    return character;
                }
            }

            shortSleep();

            if (timer.isExpired()) {
                errorCount++;
                if (errorCount == MAXERRORS) {
                    interruptTransmission();
                    throw new RuntimeException("Timeout, no data received from transmitter");
                } else {
                    // repeat transmission start request
                    outputStream.write(requestStartByte);
                    outputStream.flush();
                }
                timer.start();
            }
        }
    }

    protected int readNextBlockStart(boolean lastBlockResult) throws IOException {
        int character;
        int errorCount = 0;
        Timer timer = new Timer(1000).start();
        while (true) {
            while (inputStream.available() > 0) {
                character = inputStream.read();

                if (character == SOH || character == STX || character == EOT) {
                    return character;
                }
            }

            shortSleep();

            if (timer.isExpired()) {
                if (errorCount++ == MAXERRORS) {
                    interruptTransmission();
                    throw new RuntimeException("Timeout, no data received from transmitter");
                } else {
                    // repeat last block result and wait for next block one more time
                    outputStream.write(lastBlockResult ? ACK : NAK);
                    timer.start();
                }
            }
        }

    }

    private void shortSleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            try {
                interruptTransmission();
            } catch (IOException ignore) {
            }
            throw new RuntimeException("Transmission was interrupted", e);
        }
    }

    /**
     * send CAN to interrupt seance
     *
     * @throws java.io.IOException
     */
    protected void interruptTransmission() throws IOException {
        outputStream.write(CAN);
        outputStream.write(CAN);
    }

    protected byte[] readBlock(int blockNumber, boolean shortBlock, CRC crc) throws IOException, TimeoutException, RepeatedBlockException, SynchronizationLostException, InvalidBlockException {
        byte[] block;
        Timer timer = new Timer(1000).start();

        if (shortBlock) {
            block = shortBlockBuffer;
        } else {
            block = longBlockBuffer;
        }
        byte character;

        character = readByte(timer);

        if (character == blockNumber - 1) {
            // this is repeating of last block, possible ACK lost
            throw new RepeatedBlockException();
        }
        if (character != blockNumber) {
            // wrong block - fatal loss of synchronization
            throw new SynchronizationLostException();
        }

        character = readByte(timer);

        if (character != ~blockNumber) {
            throw new InvalidBlockException();
        }

        // data
        int i = 0;
        Lx:
        while (true) {
            while (inputStream.available() > 0) {
                if (i < block.length) {
                    block[i] = (byte) inputStream.read();
                    i++;
                } else {
                    if (!crc.readCRCAndCheck(inputStream, block)) {
                        throw new InvalidBlockException();
                    }
                    break Lx;
                }
            }

            shortSleep();

            if (timer.isExpired()) {
                throw new TimeoutException();
            }
        }

        return block;
    }

    private byte readByte(Timer timer) throws IOException, TimeoutException {
        while (true) {
            if (inputStream.available() > 0) {
                int b = inputStream.read();
                return (byte) b;
            }
            if (timer.isExpired()) {
                throw new TimeoutException();
            }
            shortSleep();
        }
    }

    class RepeatedBlockException extends Exception {
    }

    class SynchronizationLostException extends Exception {
    }

    class InvalidBlockException extends Exception {
    }

}
