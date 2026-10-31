package assetgen;

import java.util.Arrays;

public final class IntList {

    int[] a = new int[1024];
    int n;

    void add(int v) {
        if (n == a.length) a = Arrays.copyOf(a, n * 2);
        a[n++] = v;
    }
}
