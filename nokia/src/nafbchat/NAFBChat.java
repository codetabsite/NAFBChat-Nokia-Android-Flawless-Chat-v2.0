package nafbchat;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;

public class NAFBChat extends MIDlet implements CommandListener {

    private Display display;
    private Form nameForm;
    private TextField nameField;
    private Command okCmd;

    private String myName;
    private ChatScreen chatScreen;

    public void startApp() {
        display = Display.getDisplay(this);
        showNameScreen();
    }

    private void showNameScreen() {
        nameForm = new Form("NAFBChat");
        nameField = new TextField("Adın:", "", 20, TextField.ANY);
        okCmd = new Command("Tamam", Command.OK, 1);

        nameForm.append("Nokia Android Flawless\nBluetooth Chat\n\n");
        nameForm.append(nameField);
        nameForm.addCommand(okCmd);
        nameForm.setCommandListener(this);

        display.setCurrent(nameForm);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == okCmd) {
            String name = nameField.getString().trim();
            if (name.length() == 0) {
                name = "Nokia";
            }
            myName = name;
            startChat();
        }
    }

    private void startChat() {
        chatScreen = new ChatScreen(this, myName, display);
        display.setCurrent(chatScreen.getScreen());
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {
        if (chatScreen != null) {
            chatScreen.destroy();
        }
    }
}
