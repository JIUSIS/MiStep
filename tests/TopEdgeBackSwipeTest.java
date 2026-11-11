package com.jiusis.mistep;

public final class TopEdgeBackSwipeTest {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        for (int sign : new int[]{-1,1}) {
            for (float density : new float[]{1f,2.5f,3f}) {
                TopEdgeBackSwipe s=new TopEdgeBackSwipe();Object token=new Object();
                float x=sign>0?1:899;
                check(s.event(0,1,7,x,700,0,900,1426,density,sign,token)==0,"DOWN passes through");
                check(s.event(2,1,7,x+sign*20*density,700,60,900,1426,density,sign,token)==1,"inner horizontal claims");
                check(s.event(1,1,7,x+sign*70*density,700,180,900,1426,density,sign,token)==2,"one back on release");
                check(s.event(1,1,7,x+sign*70*density,700,181,900,1426,density,sign,token)==0,"no duplicate");
                for(int gate=0;gate<9;gate++) {
                    s.event(0,1,7,gate==0?450:x,700,0,900,1426,density,sign,token);
                    int action=gate==1?5:gate==2?3:2;
                    s.event(action,gate==1?2:1,gate==3?8:7,x+sign*70*density,
                            gate==4?1000:700,gate==5?800:100,900,1426,density,sign,gate==6?new Object():token);
                    int result=s.event(1,1,7,x+sign*(gate==7?30:gate==8?-70:70)*density,
                            700,gate==5?850:180,900,1426,density,sign,token);
                    check(result==0,"center/multifinger/cancel/pointer/vertical/time/lease/short/reverse stays native "+gate);
                }
                check(s.event(0,1,7,x,700,0,900,1426,density,0,token)==0&&!s.claimed(),"phone path excluded");
            }
        }
        System.out.println("TOP inner-edge back checks="+checks+" passed");
    }
}
