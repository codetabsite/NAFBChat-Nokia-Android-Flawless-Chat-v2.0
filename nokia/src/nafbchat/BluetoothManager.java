package nafbchat;

import javax.bluetooth.*;
import javax.microedition.io.*;
import java.io.*;
import java.util.*;

public class BluetoothManager implements DiscoveryListener {

    public static final String NAFB_UUID = "4E41464243686174000000000000ABCD";
    public static final String SERVICE_URL =
        "btspp://localhost:" + NAFB_UUID +
        ";name=NAFBChat;authenticate=false;encrypt=false;master=false";

    private String myName;
    private ChatScreen chatScreen;
    private LocalDevice localDevice;
    private StreamConnectionNotifier server;

    // addr → OutputStream
    private Hashtable connections = new Hashtable();
    // Mesh tekrar yayın önleme
    private Vector seenMessages = new Vector();
    // Bulunan cihazlar: [isim, adres]
    private Vector foundDevices = new Vector();

    private boolean running = false;
    private DiscoveryAgent agent;
    private boolean inquiryDone = false;

    public BluetoothManager(String myName, ChatScreen chatScreen) {
        this.myName = myName;
        this.chatScreen = chatScreen;
    }

    public void start() {
        running = true;
        try {
            localDevice = LocalDevice.getLocalDevice();
            localDevice.setDiscoverable(DiscoveryAgent.GIAC);
            agent = localDevice.getDiscoveryAgent();
            startServer();
            discover();
        } catch (BluetoothStateException e) {
            chatScreen.appendLog("[HATA] Bluetooth: " + e.getMessage());
        }
    }

    // ─── Sunucu ───────────────────────────────────────────────────────────────

    private void startServer() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    server = (StreamConnectionNotifier) Connector.open(SERVICE_URL);
                    chatScreen.setStatus("Hazır");
                    chatScreen.appendLog("[Sistem] Bağlantı bekleniyor...");
                    while (running) {
                        StreamConnection conn = server.acceptAndOpen();
                        handleConnection(conn);
                    }
                } catch (IOException e) {
                    if (running) chatScreen.appendLog("[HATA] Sunucu: " + e.getMessage());
                }
            }
        }).start();
    }

    private void handleConnection(final StreamConnection conn) {
        new Thread(new Runnable() {
            public void run() {
                String addr = "?";
                try {
                    InputStream in   = conn.openInputStream();
                    OutputStream out = conn.openOutputStream();
                    RemoteDevice rd  = RemoteDevice.getRemoteDevice(conn);
                    addr = rd.getBluetoothAddress();

                    if (connections.containsKey(addr)) { conn.close(); return; }

                    // Kendini tanıt
                    out.write(myName.getBytes("UTF-8"));
                    out.flush();

                    // Karşı tarafın adını al
                    byte[] buf = new byte[512];
                    int len = in.read(buf);
                    if (len <= 0) { conn.close(); return; }
                    String remoteName = new String(buf, 0, len, "UTF-8");

                    connections.put(addr, out);
                    chatScreen.appendLog("[+] " + remoteName + " bağlandı");
                    chatScreen.setStatus(connections.size() + " cihaz");

                    while (running) {
                        len = in.read(buf);
                        if (len <= 0) break;
                        receiveMessage(new String(buf, 0, len, "UTF-8"), addr);
                    }
                } catch (IOException e) {
                    // Bağlantı koptu
                } finally {
                    connections.remove(addr);
                    chatScreen.appendLog("[-] Bağlantı kesildi");
                    chatScreen.setStatus(connections.size() + " cihaz");
                    try { conn.close(); } catch (IOException e) {}
                }
            }
        }).start();
    }

    // ─── İstemci: keşif döngüsü ───────────────────────────────────────────────

    private void discover() {
        new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        chatScreen.appendLog("[Tarama] Cihazlar aranıyor...");
                        foundDevices.removeAllElements();
                        inquiryDone = false;
                        agent.startInquiry(DiscoveryAgent.GIAC, BluetoothManager.this);

                        // Inquiry bitene kadar bekle (max 30sn)
                        int waited = 0;
                        while (!inquiryDone && waited < 30000 && running) {
                            try { Thread.sleep(500); } catch (InterruptedException e) {}
                            waited += 500;
                        }

                        // Bulunan cihazlara bağlanmayı dene
                        for (int i = 0; i < foundDevices.size(); i++) {
                            RemoteDevice rd = (RemoteDevice) foundDevices.elementAt(i);
                            if (!connections.containsKey(rd.getBluetoothAddress())) {
                                connectToDevice(rd);
                                try { Thread.sleep(500); } catch (InterruptedException e) {}
                            }
                        }

                        try { Thread.sleep(20000); } catch (InterruptedException e) {}
                    } catch (BluetoothStateException e) {
                        try { Thread.sleep(10000); } catch (InterruptedException ie) {}
                    }
                }
            }
        }).start();
    }

    // ─── Manuel bağlantı (cihaz listesinden) ─────────────────────────────────

    public void connectToAddress(final String addr) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    RemoteDevice rd = null;
                    // Bulunan cihazlarda ara
                    for (int i = 0; i < foundDevices.size(); i++) {
                        RemoteDevice d = (RemoteDevice) foundDevices.elementAt(i);
                        if (d.getBluetoothAddress().equals(addr)) { rd = d; break; }
                    }
                    if (rd != null) {
                        connectToDevice(rd);
                    } else {
                        // Adresle direkt bağlan
                        String url = "btspp://" + addr + ":" + NAFB_UUID +
                            ";authenticate=false;encrypt=false;master=false";
                        StreamConnection conn = (StreamConnection) Connector.open(url);
                        handleConnection(conn);
                    }
                } catch (IOException e) {
                    chatScreen.appendLog("[HATA] Bağlanamadı: " + e.getMessage());
                }
            }
        }).start();
    }

    private void connectToDevice(final RemoteDevice rd) {
        new Thread(new Runnable() {
            public void run() {
                String addr = rd.getBluetoothAddress();
                if (connections.containsKey(addr)) return;
                try {
                    String url = "btspp://" + addr + ":" + NAFB_UUID +
                        ";authenticate=false;encrypt=false;master=false";
                    StreamConnection conn = (StreamConnection) Connector.open(url);
                    handleConnection(conn);
                } catch (IOException e) {
                    // Bağlanamadı
                }
            }
        }).start();
    }

    // ─── Cihaz listesi için ───────────────────────────────────────────────────

    // Döndürülen: [[isim, adres], ...]
    public String[][] getFoundDevices() {
        // Inquiry başlat ve bekle
        try {
            foundDevices.removeAllElements();
            inquiryDone = false;
            agent.startInquiry(DiscoveryAgent.GIAC, this);
            int waited = 0;
            while (!inquiryDone && waited < 15000) {
                try { Thread.sleep(500); } catch (InterruptedException e) {}
                waited += 500;
            }
        } catch (BluetoothStateException e) {
            chatScreen.appendLog("[HATA] Tarama: " + e.getMessage());
        }

        if (foundDevices.isEmpty()) return new String[0][0];

        String[][] result = new String[foundDevices.size()][2];
        for (int i = 0; i < foundDevices.size(); i++) {
            RemoteDevice rd = (RemoteDevice) foundDevices.elementAt(i);
            String name;
            try { name = rd.getFriendlyName(false); }
            catch (IOException e) { name = rd.getBluetoothAddress(); }
            if (name == null || name.length() == 0) name = rd.getBluetoothAddress();
            result[i][0] = name;
            result[i][1] = rd.getBluetoothAddress();
        }
        return result;
    }

    // ─── Mesh yayın ───────────────────────────────────────────────────────────

    private void receiveMessage(String msg, String fromAddr) {
        String id = getMsgId(msg);
        if (seenMessages.contains(id)) return;
        seenMessages.addElement(id);
        if (seenMessages.size() > 100) seenMessages.removeElementAt(0);
        chatScreen.appendLog(msg);
        broadcast(msg, fromAddr);
    }

    public void broadcast(String msg, String exceptAddr) {
        String id = getMsgId(msg);
        if (!seenMessages.contains(id)) {
            seenMessages.addElement(id);
            if (seenMessages.size() > 100) seenMessages.removeElementAt(0);
        }
        byte[] data;
        try { data = msg.getBytes("UTF-8"); }
        catch (UnsupportedEncodingException e) { data = msg.getBytes(); }

        Enumeration keys = connections.keys();
        while (keys.hasMoreElements()) {
            String addr = (String) keys.nextElement();
            if (exceptAddr != null && addr.equals(exceptAddr)) continue;
            OutputStream out = (OutputStream) connections.get(addr);
            try { out.write(data); out.flush(); }
            catch (IOException e) { connections.remove(addr); }
        }
    }

    // ─── DiscoveryListener ────────────────────────────────────────────────────

    public void deviceDiscovered(RemoteDevice rd, DeviceClass dc) {
        foundDevices.addElement(rd);
    }

    public void servicesDiscovered(int transID, ServiceRecord[] sr) {}

    public void serviceSearchCompleted(int transID, int respCode) {}

    public void inquiryCompleted(int discType) {
        inquiryDone = true;
        chatScreen.appendLog("[Tarama] " + foundDevices.size() + " cihaz bulundu");
    }

    private String getMsgId(String msg) {
        int len = msg.length();
        return msg.substring(0, len > 32 ? 32 : len);
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException e) {}
    }
}
