import externalpackage.AbstractFoo;
import externalpackage.C;
import externalpackage.I;
import externalpackage.P;
import externalpackage.F;
import externalpackage.S;

class Foo<T> extends AbstractFoo<T> implements I, Ii {

    private String a;
    private String b;
    private F f;

    private static abstract class IC<T> {
        public abstract T im(I i);
    }

    static final private IC<Integer> IF = new IC<Integer>() {
        @Override
        public Integer im(I i) {
            return i.length();
        }
    };
/*
    void aMethod() {
        float a;
        while (true) {
            int a;
            a = a + 1;
        }
        System.out.println(1);
        "".toString();
        (new Object()).toString();
        new Zum().m(null);
        Zum z = new Zum();
        z.m(null);
        z.f = 1;
        b = "";
        new Object() {
            public String toString() {
                z.m(null);
            }
        };
        C c = new C();
        c.mm();
        nn(); // pretend to call super
        m1(null);
        f.m();
        S.m();
    }

    private void m1(P p) {
    }
*/
}
