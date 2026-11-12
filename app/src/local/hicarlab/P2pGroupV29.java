package local.hicarlab;

import android.net.wifi.p2p.*;
import java.security.SecureRandom;
import java.util.Locale;

/** API 29+ only. Kept in a separate class and loaded reflectively so that
 *  Android 5.1 / 9 never resolves WifiP2pConfig.Builder or the three-arg
 *  createGroup() while loading P2pHotspot. */
public final class P2pGroupV29 {
    public static void create(WifiP2pManager manager, WifiP2pManager.Channel channel,
                       P2pHotspot.Events events, WifiP2pManager.ActionListener listener){
        byte[] random=new byte[12];new SecureRandom().nextBytes(random);
        StringBuilder password=new StringBuilder();
        for(byte b:random)password.append(String.format(Locale.ROOT,"%02x",b&255));
        WifiP2pConfig config=new WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-HLHiCarLab-"+password.substring(0,4))
                .setPassphrase(password.toString())
                .setGroupOperatingFrequency(5745)
                .enablePersistentMode(false).build();
        events.log("正在创建本轮临时 Wi-Fi Direct 热点（5 GHz 信道 149）；不记录密码。");
        manager.createGroup(channel,config,listener);
    }
}
