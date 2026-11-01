package local.hicarlab;
import java.io.*;
import java.net.*;
import java.util.*;

/** Read only the already created GO interface using ordinary app privileges. */
final class OwnGroupAddress {
    static boolean valid(String s){return s!=null&&s.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")&&!s.equalsIgnoreCase("02:00:00:00:00:00")&&!s.equals("00:00:00:00:00:00")&&(Integer.parseInt(s.substring(0,2),16)&1)==0;}
    static String format(byte[] bytes){
        if(bytes==null||bytes.length!=6)return null;StringBuilder out=new StringBuilder();
        for(byte b:bytes){if(out.length()>0)out.append(':');out.append(String.format(Locale.ROOT,"%02X",b&255));}
        return valid(out.toString())?out.toString():null;
    }
    static String read(NetworkInterface network,String name,P2pHotspot.Events events){
        if(network==null||name==null||!name.matches("[A-Za-z0-9_.:-]{1,32}")||!name.equals(network.getName()))return null;
        String hardware=null,file=null;
        try{hardware=format(network.getHardwareAddress());events.log("本轮热点接口公开地址读取："+(hardware!=null?"取得有效值（不写日志）":"未提供有效值"));}
        catch(Exception e){events.log("本轮热点接口公开地址读取未获支持："+e.getClass().getSimpleName());}
        try(BufferedReader reader=new BufferedReader(new FileReader("/sys/class/net/"+name+"/address"))){
            char[] chars=new char[19];int n=reader.read(chars);String value=n>0?new String(chars,0,n).trim():"";Arrays.fill(chars,'\0');if(valid(value))file=value.toUpperCase(Locale.ROOT);
            events.log("本轮热点接口普通文件读取："+(file!=null?"取得有效值（不写日志）":"未提供有效值"));
        }catch(Exception e){events.log("本轮热点接口普通文件读取未获支持："+e.getClass().getSimpleName());}
        if(hardware!=null&&file!=null&&!hardware.equals(file)){events.log("本轮热点接口地址来源不一致，未采用。");return null;}
        return hardware!=null?hardware:file;
    }
}
