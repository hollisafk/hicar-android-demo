package local.hicarlab;
public final class WireIdentityTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  String expected="EEDFDE64B5E4FB01A23778C4310F7BB41818DED91040CD2BDFA02D7873473F83";
  check(WireIdentity.derive("00048000","aa:bb:cc:dd:ee:ff").equals(expected));
  check(WireIdentity.derive("aBcD8000","AA:BB:CC:DD:EE:FF").equals(WireIdentity.derive("ABCD8000","aa:bb:cc:dd:ee:ff")));
  check(!WireIdentity.derive("00048001","AA:BB:CC:DD:EE:FF").equals(expected));
  for(String address:new String[]{null,"02:00:00:00:00:00","00:00:00:00:00:00","FF:FF:FF:FF:FF:FF","AA:BB:CC:DD:EE","AABBCCDDEEFF"}){
   boolean bad=false;try{WireIdentity.derive("00048000",address);}catch(IllegalArgumentException e){bad=true;}check(bad);
  }
  String seed="11";check(WireIdentity.select(seed,"00048000","00048000",expected).equals(expected));
  check(WireIdentity.select(seed,"00048001","00048000",expected).equals(seed));
  check(WireIdentity.select(seed,"00048000","00048000","bad").equals(seed));
  check(seed.equals("11"));
  System.out.println("PASS: upstream SHA256 fixture, normalization, masked/multicast rejection, model binding and seed fallback");
 }
}
