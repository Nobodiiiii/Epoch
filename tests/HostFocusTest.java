import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

/** Controlled Win32 observations and calls: no real windows, clicks or desktop changes. */
public final class HostFocusTest {
    static final HWND ROOT=window(1), CHILD=window(2), STANDBY=window(3), OTHER=window(4);
    static final long OWNER=42;
    static final int OUR_THREAD=10, CHILD_THREAD=20;
    static int checks;
    static HWND window(long value){return new HWND(new Pointer(value));}
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}

    static final class Fake {
        HWND foreground=ROOT,active=ROOT,focus=ROOT,ancestor=ROOT,capture,menu,move;
        Set<HWND> invalid=new HashSet<>(),hidden=new HashSet<>(),disabled=new HashSet<>();
        int flags,thread=CHILD_THREAD,attached,detached,focused,peeks;
        long owner=OWNER;
        boolean iconic,readable=true,attachSucceeds=true,focusSucceeds=true,throwOnFocus;
        Runnable onAttach=()->{};
        final EpochHost.Win api=(EpochHost.Win)Proxy.newProxyInstance(EpochHost.Win.class.getClassLoader(),
            new Class<?>[]{EpochHost.Win.class},(proxy,method,args)->{
                switch(method.getName()){
                    case "IsWindow":return !invalid.contains((HWND)args[0]);
                    case "IsWindowVisible":return !hidden.contains((HWND)args[0]);
                    case "IsWindowEnabled":return !disabled.contains((HWND)args[0]);
                    case "IsIconic":return iconic;
                    case "GetForegroundWindow":return foreground;
                    case "GetAncestor":return ancestor;
                    case "GetWindowThreadProcessId":((IntByReference)args[1]).setValue((int)owner);return thread;
                    case "GetGUIThreadInfo":
                        check((int)args[0]==thread,"query the target input queue");
                        WinUser.GUITHREADINFO info=(WinUser.GUITHREADINFO)args[1];
                        check(info.cbSize==info.size(),"GUITHREADINFO initialized for Win32");
                        info.hwndActive=active;info.hwndFocus=focus;info.flags=flags;
                        info.hwndCapture=capture;info.hwndMenuOwner=menu;info.hwndMoveSize=move;
                        return readable;
                    case "PeekMessage":peeks++;return false;
                    case "AttachThreadInput":
                        check((int)args[0]==OUR_THREAD&&(int)args[1]==CHILD_THREAD,"attach correct input queues");
                        if((boolean)args[2]){attached++;check(peeks>0,"create message queue before attaching");onAttach.run();return attachSucceeds;}
                        detached++;return true;
                    case "SetFocus":
                        focused++;
                        if(throwOnFocus)throw new IllegalStateException("simulated SetFocus failure");
                        HWND previous=focus;if(focusSucceeds)focus=(HWND)args[0];return previous;
                    default:throw new AssertionError("Unexpected native call: "+method.getName());
                }
            });
        boolean restore(){return EpochHost.FocusRouter.restore(api,OUR_THREAD,ROOT,CHILD,OWNER,List.of(ROOT,CHILD,STANDBY));}
    }
    static void skipped(String reason,Consumer<Fake> configure){
        Fake f=new Fake();configure.accept(f);
        check(!f.restore(),reason+": no recovery reported");
        check(f.focused==0&&f.attached==0&&f.detached==0&&f.peeks==0,reason+": no input mutation");
    }
    static void raced(String reason,Consumer<Fake> configure){
        Fake f=new Fake();f.onAttach=()->configure.accept(f);
        check(!f.restore(),reason+": aborted after attachment");
        check(f.focused==0&&f.attached==1&&f.detached==1,reason+": detached without setting focus");
    }
    public static void main(String[] args){
        Fake f=new Fake();
        check(f.restore()&&CHILD.equals(f.focus),"reactivated source routes focus to visible target");
        check(f.attached==1&&f.detached==1&&f.focused==1,"exactly one balanced transfer");
        check(!f.restore()&&f.attached==1,"steady foreground does not reattach or reset key state");
        for(int i=0;i<3;i++){
            f.foreground=OTHER;f.focus=null;
            check(!f.restore(),"Alt-Tab away keeps other application active");
            f.foreground=ROOT;f.focus=ROOT;
            check(f.restore(),"returning to root restores child every time");
        }
        check(f.attached==4&&f.detached==4,"repeated reactivation balances all attachments");
        Fake lost=new Fake();lost.focus=null;check(lost.restore(),"null focus can recover");
        Fake sibling=new Fake();sibling.focus=STANDBY;check(sibling.restore(),"inactive game surface can recover");
        Fake root=new Fake();root.focus=CHILD;
        check(EpochHost.FocusRouter.restore(root.api,OUR_THREAD,ROOT,ROOT,OWNER,List.of(ROOT,CHILD)),"handoff back to original root");
        check(ROOT.equals(root.focus),"return handoff targets source");

        skipped("background root",x->x.foreground=OTHER);
        skipped("no foreground",x->x.foreground=null);
        skipped("already focused",x->x.focus=CHILD);
        skipped("minimized",x->x.iconic=true);
        skipped("hidden target",x->x.hidden.add(CHILD));
        skipped("hidden root",x->x.hidden.add(ROOT));
        skipped("disabled target",x->x.disabled.add(CHILD));
        skipped("disabled modal root",x->x.disabled.add(ROOT));
        skipped("destroyed target",x->x.invalid.add(CHILD));
        skipped("destroyed root",x->x.invalid.add(ROOT));
        skipped("reused HWND owner",x->x.owner=99);
        skipped("detached child",x->x.ancestor=OTHER);
        skipped("missing thread",x->x.thread=0);
        skipped("inaccessible input queue",x->x.readable=false);
        skipped("owned dialog active",x->x.active=OTHER);
        skipped("unrelated control focused",x->x.focus=OTHER);
        skipped("mouse capture",x->x.capture=ROOT);
        skipped("menu owner",x->x.menu=ROOT);
        skipped("drag owner",x->x.move=ROOT);
        for(int flag:new int[]{2,4,8,16})skipped("move/menu flag "+flag,x->x.flags=flag);

        raced("foreground changed",x->x.foreground=OTHER);
        raced("target hidden",x->x.hidden.add(CHILD));
        raced("target destroyed",x->x.invalid.add(CHILD));
        raced("owner changed",x->x.owner=99);
        raced("focus already recovered",x->x.focus=CHILD);
        raced("menu opened",x->x.flags=4);
        Fake denied=new Fake();denied.attachSucceeds=false;
        check(!denied.restore()&&denied.focused==0&&denied.detached==0,"failed attachment never calls SetFocus");
        Fake refused=new Fake();refused.focusSucceeds=false;
        check(!refused.restore()&&refused.detached==1,"unconfirmed focus does not report success");
        Fake failure=new Fake();failure.throwOnFocus=true;
        try{failure.restore();throw new AssertionError("expected simulated failure");}
        catch(IllegalStateException expected){check(failure.detached==1,"exception still detaches input queues");}
        Fake local=new Fake();local.thread=OUR_THREAD;
        check(local.restore()&&local.attached==0&&local.detached==0,"same-thread route needs no attachment");
        System.out.println("PASS host focus: "+checks+" checks; repeated reactivation, background/menu guards, races, queue cleanup");
    }
}
