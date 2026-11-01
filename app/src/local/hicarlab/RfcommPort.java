package local.hicarlab;

import java.util.regex.*;

/** RFCOMM port retrieval. getPsm is specified only for L2CAP sockets. */
final class RfcommPort {
    static int valid(int value) {return value>=1&&value<=30?value:-1;}
    static int fromDescription(String description) {
        if(description==null||description.length()>1024||!description.contains("TYPE_RFCOMM"))return -1;
        Matcher m=Pattern.compile("\\bChannel:\\s*([0-9]+)\\b").matcher(description);
        if(!m.find())return -1;
        int result;
        try {result=valid(Integer.parseInt(m.group(1)));}catch(NumberFormatException e){return -1;}
        return m.find()?-1:result;
    }
}
