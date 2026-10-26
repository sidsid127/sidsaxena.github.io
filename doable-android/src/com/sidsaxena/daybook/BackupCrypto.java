package com.sidsaxena.daybook;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-encrypted backup stream: PBKDF2 key from the backup password, data in 1 MB AES-256-GCM chunks so
 * large photo collections never need to fit in memory. Each chunk's number and an "is last" flag are
 * authenticated, so a cut-off or reordered file is detected.
 */
final class BackupCrypto {
    private BackupCrypto() {}

    static final byte[] MAGIC = {'D', 'O', 'A', 'B', 'L', 'E', 'B', 'K', '1'};
    private static final int CHUNK = 1 << 20;
    static final int ROUNDS = 150000;

    static final class WrongPassword extends IOException {
        WrongPassword() { super("Wrong password or damaged file"); }
    }

    static SecretKey key(char[] password, byte[] salt, int rounds, boolean sha256) throws GeneralSecurityException {
        SecretKeyFactory f;
        try {
            f = SecretKeyFactory.getInstance(sha256 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1");
        } catch (GeneralSecurityException e) {
            if (!sha256) throw e;
            return null;
        }
        byte[] k = f.generateSecret(new PBEKeySpec(password, salt, rounds, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    /** Wraps {@code out}; everything written is encrypted. Close it to finish the file. */
    static OutputStream encrypt(OutputStream out, char[] password) throws IOException, GeneralSecurityException {
        byte[] salt = new byte[16];
        SecureRandom rnd = new SecureRandom();
        rnd.nextBytes(salt);
        SecretKey k = key(password, salt, ROUNDS, true);
        boolean sha256 = k != null;
        if (k == null) k = key(password, salt, ROUNDS, false);
        final DataOutputStream d = new DataOutputStream(out);
        d.write(MAGIC);
        d.writeByte(sha256 ? 1 : 0);
        d.writeInt(ROUNDS);
        d.write(salt);
        final byte[] nonce = new byte[8];
        rnd.nextBytes(nonce);
        d.write(nonce);
        final SecretKey key = k;
        return new FilterOutputStream(d) {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            private int counter;
            private boolean closed;

            @Override
            public void write(int b) throws IOException {
                buf.write(b);
                if (buf.size() >= CHUNK) flushChunk(false);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                while (len > 0) {
                    int n = Math.min(len, CHUNK - buf.size());
                    buf.write(b, off, n);
                    off += n;
                    len -= n;
                    if (buf.size() >= CHUNK) flushChunk(false);
                }
            }

            private void flushChunk(boolean last) throws IOException {
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv(nonce, counter)));
                    c.updateAAD(aad(counter, last));
                    byte[] ct = c.doFinal(buf.toByteArray());
                    d.writeInt(ct.length);
                    d.writeByte(last ? 1 : 0);
                    d.write(ct);
                    counter++;
                    buf.reset();
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
                }
            }

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                flushChunk(true);
                d.close();
            }
        };
    }

    /** Reads an encrypted backup; throws WrongPassword if the password is wrong or the file was changed. */
    static InputStream decrypt(InputStream in, char[] password) throws IOException, GeneralSecurityException {
        final DataInputStream d = new DataInputStream(in);
        byte[] magic = new byte[MAGIC.length];
        d.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("This is not a Doable backup file");
        boolean sha256 = d.readByte() == 1;
        int rounds = d.readInt();
        byte[] salt = new byte[16];
        d.readFully(salt);
        final byte[] nonce = new byte[8];
        d.readFully(nonce);
        final SecretKey key = key(password, salt, rounds, sha256);
        if (key == null) throw new IOException("This phone cannot read backups made on newer Android versions");
        return new InputStream() {
            private byte[] chunk = new byte[0];
            private int pos, counter;
            private boolean done;

            private boolean fill() throws IOException {
                while (pos >= chunk.length) {
                    if (done) return false;
                    boolean last;
                    byte[] ct;
                    try {
                        int len = d.readInt();
                        last = d.readByte() == 1;
                        if (len < 0 || len > CHUNK + 64) throw new IOException("The backup file is damaged");
                        ct = new byte[len];
                        d.readFully(ct);
                    } catch (EOFException e) {
                        throw new IOException("The backup file is incomplete");
                    }
                    try {
                        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv(nonce, counter)));
                        c.updateAAD(aad(counter, last));
                        chunk = c.doFinal(ct);
                    } catch (GeneralSecurityException e) {
                        throw new WrongPassword();
                    }
                    counter++;
                    pos = 0;
                    done = last;
                }
                return true;
            }

            @Override
            public int read() throws IOException {
                if (!fill()) return -1;
                return chunk[pos++] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (!fill()) return -1;
                int n = Math.min(len, chunk.length - pos);
                System.arraycopy(chunk, pos, b, off, n);
                pos += n;
                return n;
            }

            @Override
            public void close() throws IOException {
                d.close();
            }
        };
    }

    private static byte[] iv(byte[] nonce, int counter) {
        byte[] iv = new byte[12];
        System.arraycopy(nonce, 0, iv, 0, 8);
        iv[8] = (byte) (counter >>> 24);
        iv[9] = (byte) (counter >>> 16);
        iv[10] = (byte) (counter >>> 8);
        iv[11] = (byte) counter;
        return iv;
    }

    private static byte[] aad(int counter, boolean last) {
        return new byte[]{(byte) (counter >>> 24), (byte) (counter >>> 16), (byte) (counter >>> 8), (byte) counter,
                (byte) (last ? 1 : 0)};
    }
}
