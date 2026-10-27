import os
"""Exercise Host's actual live cell identity gate across token parcel updates."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
source = (root/'module/src/main/java/com/jianguo/mistep/MiStepHost.java').read_text(encoding='utf-8-sig')
out = Path(sys.argv[1]) if len(sys.argv)>1 else root/'build-cell-binding-jvm'
out.mkdir(parents=True, exist_ok=True)
start = source.index('    private boolean cellMoveCurrent(')
brace = source.index('{',start)
depth,end=1,brace+1
while depth:
    if source[end]=='{':depth+=1
    elif source[end]=='}':depth-=1
    end+=1
body = r'''
public class CellBindingRegression {
 static int checks;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static class View {} static class FrameLayout extends View {}
 static class Surface {boolean valid=true;}
 static class Controller {Surface surface=new Surface();}
 static class Token {
  final Object binder; Token(Object binder){this.binder=binder;}
  @Override public boolean equals(Object value){return value instanceof Token&&((Token)value).binder==binder;}
  @Override public int hashCode(){return System.identityHashCode(binder);}
 }
 static class Slot {int index=2,taskId=74;long taskEpoch=8;Object taskToken=new Token(new Object());
  Controller controller=new Controller();View view=new View();}
 static class XposedHelpers {static Object callMethod(Object value,String method){return ((Surface)value).valid;}}
 FrameLayout root=new FrameLayout();long sceneEpoch=5;Slot slot=new Slot();int logs;
 boolean isActive(Slot value){return value==slot;}
 Object getSurfaceField(Object value,String field){return ((Controller)value).surface;}
 void log(String value){logs++;}
 __METHOD__
 boolean current(Object token){return cellMoveCurrent(slot,root,5,8,74,token,slot.controller,slot.view,slot.controller.surface);}
 public static void main(String[] args){
  CellBindingRegression h=new CellBindingRegression();Object original=h.slot.taskToken;
  check(h.current(original),"original real task binding continues");
  h.slot.taskToken=new Token(((Token)original).binder);
  check(h.slot.taskToken!=original&&h.slot.taskToken.equals(original),"fixture parcels same task binder into another wrapper");
  check(h.current(original),"same Binder new WindowContainerToken wrapper must preserve cell motion");
  check(h.logs==0,"equivalent token update must not end or log an aborted motion");
  for(int change=0;change<10;change++){
   h=new CellBindingRegression();Slot slot=h.slot;FrameLayout root=h.root;View view=slot.view;
   Object token=slot.taskToken,controller=slot.controller,surface=slot.controller.surface;
   if(change==0)h.root=new FrameLayout();
   else if(change==1)h.sceneEpoch++;
   else if(change==2)h.slot=new Slot();
   else if(change==3)slot.taskEpoch++;
   else if(change==4)slot.taskId++;
   else if(change==5)slot.taskToken=new Token(new Object());
   else if(change==6)slot.controller=new Controller();
   else if(change==7)slot.view=new View();
   else if(change==8)slot.controller.surface=new Surface();
   else ((Surface)surface).valid=false;
   check(!h.cellMoveCurrent(slot,root,5,8,74,token,controller,view,surface),"changed real binding must stop: "+change);
   check(h.logs==1,"aborted identity has one diagnostic: "+change);
  }
  System.out.println("actual Host cell binding checks="+checks+" passed");
 }
}
'''.replace('__METHOD__',source[start:end])
java=out/'CellBindingRegression.java';java.write_text(body,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(java)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'CellBindingRegression'],check=True)
