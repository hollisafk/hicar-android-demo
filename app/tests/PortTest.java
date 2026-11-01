package local.hicarlab;
public class PortTest {
 static void check(String s,int expected){if(RfcommPort.fromDescription(s)!=expected)throw new AssertionError(s);}
 public static void main(String[] args){
  check("ServerSocket: Type: TYPE_RFCOMM Channel: 7",7);
  check("ServerSocket: Type: TYPE_L2CAP Channel: 7",-1);
  check("ServerSocket: Type: TYPE_RFCOMM Channel: -1",-1);
  check("ServerSocket: Type: TYPE_RFCOMM Channel: 0",-1);
  check("ServerSocket: Type: TYPE_RFCOMM Channel: 31",-1);
  check("ServerSocket: Type: TYPE_RFCOMM Channel: 999999999999999999999",-1);
  check("ServerSocket: Type: TYPE_RFCOMM Channel: 3 Channel: 4",-1);
  check(null,-1);check("",-1);
  for(int i=1;i<=30;i++)check("ServerSocket: Type: TYPE_RFCOMM Channel: "+i,i);
  System.out.println("39 RFCOMM descriptor cases passed");
 }
}
