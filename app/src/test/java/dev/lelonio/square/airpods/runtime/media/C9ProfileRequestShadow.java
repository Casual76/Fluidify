// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBluetoothAdapter;

/** Counts Android profile requests without inventing an A2DP route or a media receipt. */
@Implements(BluetoothAdapter.class)
public class C9ProfileRequestShadow extends ShadowBluetoothAdapter {
    public int requests;
    @Implementation
    public boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener, int profile) {
        if (profile == BluetoothProfile.A2DP) requests++;
        return true;
    }
}
