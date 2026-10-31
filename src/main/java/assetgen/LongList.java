package assetgen;

import java.util.Arrays;

public final class LongList {

    long[] a = new long[1024];
    int n;

    void add(long v) {
        if (n == a.length) a = Arrays.copyOf(a, n * 2);
        a[n++] = v;
    }
}
