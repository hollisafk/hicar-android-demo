package local.hicarlab;
import java.util.*;

/** Match only fresh observations of this reservation's exact SSID and band. */
final class ApScanMatch {
    static final class Entry {
        final String ssid,bssid; final int frequency; final long timestamp;
        Entry(String s,String b,int f,long t){ssid=s;bssid=b;frequency=f;timestamp=t;}
    }
    static String select(String ssid,int frequency,long since,List<Entry> entries){
        Set<String> matches=new HashSet<>();
        for(Entry e:entries){
            if(!ssid.equals(e.ssid)||e.frequency!=frequency||e.timestamp<since||e.bssid==null)continue;
            String b=e.bssid.toLowerCase(Locale.ROOT);
            if(!b.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")||b.equals("02:00:00:00:00:00")||b.equals("00:00:00:00:00:00")||b.equals("ff:ff:ff:ff:ff:ff"))continue;
            if((Integer.parseInt(b.substring(0,2),16)&1)!=0)continue;
            matches.add(b);
        }
        return matches.size()==1?matches.iterator().next():null;
    }
}
