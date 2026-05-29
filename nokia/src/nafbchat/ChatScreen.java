package nafbchat;

import javax.microedition.lcdui.*;

public class ChatScreen implements CommandListener {

    private NAFBChat midlet;
    private Display display;
    private String myName;

    private Form chatForm;
    private TextField msgField;
    private StringItem chatLog;
    private Command sendCmd, devicesCmd, backCmd;

    private BluetoothManager btManager;
    private StringBuffer logBuffer;

    // Cihaz listesi ekranı
    private List deviceList;
    private Command connectCmd, cancelCmd;
    private String[] foundAddrs;
    private Command deviceBackCmd;

    public ChatScreen(NAFBChat midlet, String myName, Display display) {
        this.midlet = midlet;
        this.myName = myName;
        this.display = display;
        this.logBuffer = new StringBuffer();
        buildUI();
        startBluetooth();
    }

    private void buildUI() {
        chatForm = new Form("NAFBChat");

        chatLog = new StringItem("", "Başlatılıyor...\n");
        chatLog.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);

        msgField = new TextField("", "", 160, TextField.ANY);
        msgField.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);

        sendCmd    = new Command("Gönder",  Command.OK,     1);
        devicesCmd = new Command("Cihazlar",Command.SCREEN, 2);
        backCmd    = new Command("Çıkış",   Command.BACK,   3);

        chatForm.append(chatLog);
        chatForm.append(msgField);
        chatForm.addCommand(sendCmd);
        chatForm.addCommand(devicesCmd);
        chatForm.addCommand(backCmd);
        chatForm.setCommandListener(this);
    }

    private void startBluetooth() {
        btManager = new BluetoothManager(myName, this);
        new Thread(new Runnable() {
            public void run() { btManager.start(); }
        }).start();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == sendCmd) {
            String text = msgField.getString().trim();
            if (text.length() > 0) {
                String msg = myName + ": " + text;
                appendLog(msg);
                btManager.broadcast(msg, null);
                msgField.setString("");
            }
        } else if (c == devicesCmd) {
            showDeviceList();
        } else if (c == backCmd) {
            destroy();
            midlet.notifyDestroyed();
        } else if (c == connectCmd) {
            // Listeden seçilen cihaza bağlan
            int idx = deviceList.getSelectedIndex();
            if (idx >= 0 && foundAddrs != null && idx < foundAddrs.length) {
                String addr = foundAddrs[idx];
                appendLog("[Bağlanıyor] " + deviceList.getString(idx) + "...");
                btManager.connectToAddress(addr);
            }
            display.setCurrent(chatForm);
        } else if (c == deviceBackCmd) {
            display.setCurrent(chatForm);
        }
    }

    // Cihaz listesini göster
    private void showDeviceList() {
        appendLog("[Tarama] Cihazlar aranıyor...");
        new Thread(new Runnable() {
            public void run() {
                // BluetoothManager'dan bulunan cihazları al
                final String[][] devices = btManager.getFoundDevices();
                Display.getDisplay(midlet).callSerially(new Runnable() {
                    public void run() {
                        if (devices == null || devices.length == 0) {
                            appendLog("[Tarama] Cihaz bulunamadı");
                            return;
                        }
                        deviceList = new List("Cihazlar", List.IMPLICIT);
                        foundAddrs = new String[devices.length];
                        for (int i = 0; i < devices.length; i++) {
                            deviceList.append(devices[i][0], null); // isim
                            foundAddrs[i] = devices[i][1];          // adres
                        }
                        connectCmd    = new Command("Bağlan", Command.OK,   1);
                        deviceBackCmd = new Command("Geri",   Command.BACK, 2);
                        deviceList.addCommand(connectCmd);
                        deviceList.addCommand(deviceBackCmd);
                        deviceList.setCommandListener(ChatScreen.this);
                        display.setCurrent(deviceList);
                    }
                });
            }
        }).start();
    }

    public void appendLog(final String line) {
        logBuffer.append(line).append("\n");
        String full = logBuffer.toString();
        if (full.length() > 600) {
            full = full.substring(full.length() - 600);
        }
        final String text = full;
        Display.getDisplay(midlet).callSerially(new Runnable() {
            public void run() { chatLog.setText(text); }
        });
    }

    public void setStatus(final String status) {
        Display.getDisplay(midlet).callSerially(new Runnable() {
            public void run() { chatForm.setTitle("NAFBChat [" + status + "]"); }
        });
    }

    public Displayable getScreen() { return chatForm; }

    public void destroy() {
        if (btManager != null) btManager.stop();
    }
}
