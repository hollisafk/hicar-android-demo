package local.hicarlab;
public final class ReceiverStatusTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  ReceiverStatus s=new ReceiverStatus();check(s.snapshot(true).title.equals("已停止")&&!s.snapshot(true).receiving);
  s.begin(7);s.advance(7,ReceiverStatus.Stage.VERIFYING);s.advance(7,ReceiverStatus.Stage.NETWORK);
  s.advance(7,ReceiverStatus.Stage.WAITING);check(s.snapshot(false).title.equals("正在连接无线网络"));
  s.issue(6,"stale");check(!s.snapshot(false).problem);
  s.advance(7,ReceiverStatus.Stage.MEDIA);check(!s.snapshot(false).receiving&&s.snapshot(false).title.equals("等待手机画面"));
  check(s.snapshot(true).receiving);
  s.stopped(8);s.advance(7,ReceiverStatus.Stage.MEDIA);s.issue(7,"old disconnect");check(!s.snapshot(true).receiving&&!s.snapshot(true).problem);
  s.failed(9,"蓝牙已关闭");s.advance(9,ReceiverStatus.Stage.WAITING);check(s.snapshot(true).problem&&!s.snapshot(true).receiving&&s.snapshot(true).detail.equals("蓝牙已关闭"));
  s.retrying(10);check(s.snapshot(true).problem&&!s.snapshot(true).receiving);s.advance(9,ReceiverStatus.Stage.MEDIA);check(s.snapshot(false).title.equals("正在重新连接"));
  s.begin(11);s.advance(11,ReceiverStatus.Stage.WAITING);check(!s.snapshot(false).problem);
  s.issue(11,"请检查连接码");check(s.snapshot(false).problem);s.advance(11,ReceiverStatus.Stage.NETWORK);check(!s.snapshot(false).problem);
  System.out.println("PASS: no false receiving before decoded media, delayed callback cannot regress stage or revive stopped session, failure/retry visibility and fresh-session recovery");
 }
}
