package com.probrowser.app.data;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.probrowser.app.AppConfig;
import com.probrowser.app.model.BrowserConfig;
import com.probrowser.app.model.PopupNotification;
import com.probrowser.app.model.ProxyServer;

import java.util.ArrayList;
import java.util.List;

public class FirebaseRepository {
    public interface Listener {
        void onConfigChanged(BrowserConfig config);
        void onProxiesChanged(List<ProxyServer> proxies);
        void onPopupChanged(PopupNotification popup);
        void onError(String message);
    }

    private final DatabaseReference root;

    public FirebaseRepository(FirebaseDatabase database) {
        root = database.getReference().child("apps").child(AppConfig.APP_KEY);
    }

    public void start(Listener listener) {
        root.child("browser_config").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                BrowserConfig config = snapshot.getValue(BrowserConfig.class);
                listener.onConfigChanged(config == null ? new BrowserConfig() : config);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                listener.onError(error.getMessage());
            }
        });

        root.child("proxies").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<ProxyServer> result = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    ProxyServer proxy = child.getValue(ProxyServer.class);
                    if (proxy != null && proxy.enabled) {
                        proxy.id = child.getKey();
                        result.add(proxy);
                    }
                }
                listener.onProxiesChanged(result);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                listener.onError(error.getMessage());
            }
        });

        root.child("popup_notification").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                PopupNotification popup = snapshot.getValue(PopupNotification.class);
                listener.onPopupChanged(popup == null ? new PopupNotification() : popup);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                listener.onError(error.getMessage());
            }
        });
    }
}
