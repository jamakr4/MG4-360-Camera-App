package com.drivehub.kamera.birdseye;

import java.io.*;
import java.util.zip.CRC32;

/** Durable write-ahead ledger: an unacknowledged attempt survives a killed process or reboot. */
final class StartupLoadGuard {
    static final int LIMIT=5;
    interface Store { State read() throws IOException; void write(State state) throws IOException; }
    static final class State {
        final int attempts;final long serial;final boolean automatic;
        State(int attempts,long serial,boolean automatic){this.attempts=attempts;this.serial=serial;this.automatic=automatic;}
    }
    private final Store store;
    StartupLoadGuard(Store store){this.store=store;}
    State state() throws IOException {return store.read();}
    State begin(boolean automatic) throws IOException {
        State old=store.read();
        if(old.attempts>=LIMIT||(automatic&&!old.automatic))return null;
        if(old.serial==Long.MAX_VALUE)throw new IOException("Guard serial exhausted");
        State next=new State(old.attempts+1,old.serial+1,old.automatic);
        store.write(next); // Must finish durably before the caller starts any model work.
        return next;
    }
    void stable(long serial) throws IOException {
        State old=store.read();
        if(old.serial==serial&&old.attempts>0)store.write(new State(0,serial,old.automatic));
    }
    void automatic(boolean enabled) throws IOException {
        State old=store.read();store.write(new State(old.attempts,old.serial,enabled));
    }
    void reset() throws IOException {
        State old;try{old=store.read();}catch(IOException corrupt){old=new State(0,0,false);}
        store.write(new State(0,old.serial==Long.MAX_VALUE?0:old.serial+1,old.automatic));
    }
    static byte[] encode(State state) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x4d473447);out.writeInt(1);out.writeInt(state.attempts);out.writeLong(state.serial);out.writeBoolean(state.automatic);
        CRC32 crc=new CRC32();crc.update(bytes.toByteArray());out.writeInt((int)crc.getValue());return bytes.toByteArray();
    }
    static State decode(byte[] bytes) throws IOException {
        if(bytes.length!=25)throw new IOException("Invalid startup ledger length");
        CRC32 crc=new CRC32();crc.update(bytes,0,21);DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=0x4d473447||in.readInt()!=1)throw new IOException("Invalid startup ledger header");
        int attempts=in.readInt();long serial=in.readLong();int automatic=in.readUnsignedByte();
        if(attempts<0||attempts>LIMIT||serial<0||automatic>1||in.readInt()!=(int)crc.getValue())throw new IOException("Invalid startup ledger data");
        return new State(attempts,serial,automatic==1);
    }
}
