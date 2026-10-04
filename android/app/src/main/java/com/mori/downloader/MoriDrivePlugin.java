package com.mori.downloader;

import android.app.Activity;
import android.content.Intent;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;

@CapacitorPlugin(name = "MoriDrive")
public class MoriDrivePlugin extends Plugin {
    private boolean connecting;

    @PluginMethod
    public void request(PluginCall call) {
        String action = call.getString("action", "");
        JSONObject options = call.getObject("options", new JSObject());
        try {
            MoriDriveBackend backend = MoriDriveBackend.get(getContext());
            switch (action) {
                case "status":
                    resolve(call, backend.status());
                    break;
                case "acknowledge":
                    resolve(call, backend.acknowledge(options.getString("receiptId")));
                    break;
                case "setEnabled":
                    if (!(options.opt("enabled") instanceof Boolean)) {
                        call.reject("enabled must be a boolean", "INVALID_OPTIONS");
                        return;
                    }
                    backend.setEnabled(options.getBoolean("enabled"));
                    resolve(call, backend.status());
                    break;
                case "disconnect":
                    backend.disconnect();
                    resolve(call, backend.status());
                    break;
                case "connect":
                    getActivity().runOnUiThread(() -> {
                        if (connecting) {
                            call.reject("Authorization already in progress", "BUSY");
                            return;
                        }
                        connecting = true;
                        try {
                            startActivityForResult(call,
                                new Intent(getContext(), MoriDriveAuthorizationActivity.class), "connected");
                        } catch (Exception ignored) {
                            connecting = false;
                            call.reject("Drive authorization unavailable", "AUTHORIZATION_FAILED");
                        }
                    });
                    break;
                case "download":
                case "saveBytes":
                    String jobId = backend.createJob(options, "saveBytes".equals(action), false);
                    backend.run(jobId, options.optString("data", null), (result) -> resolve(call, result));
                    break;
                case "retry":
                    backend.run(options.getString("jobId"), null, (result) -> resolve(call, result));
                    break;
                default:
                    call.reject("Unsupported Android Drive action", "INVALID_ACTION");
            }
        } catch (Exception e) {
            call.reject(MoriDriveBackend.safeError(e), "DRIVE_ERROR");
        }
    }

    @ActivityCallback
    private void connected(PluginCall call, ActivityResult result) {
        connecting = false;
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK) {
            call.reject("Drive authorization cancelled or unavailable", "AUTHORIZATION_FAILED");
            return;
        }
        try {
            resolve(call, MoriDriveBackend.get(getContext()).status());
        } catch (Exception e) {
            call.reject(MoriDriveBackend.safeError(e), "DRIVE_ERROR");
        }
    }

    private void resolve(PluginCall call, JSONObject result) {
        try {
            if (result.has("ok") && !result.getBoolean("ok") && !result.optBoolean("uploaded")) {
                call.reject(result.optString("error", "DRIVE_OPERATION_FAILED")
                    + " (pending job " + result.optString("jobId", "") + ")", "DRIVE_ERROR");
                return;
            }
            call.resolve(new JSObject(result.toString()));
        } catch (Exception ignored) {
            call.reject("Drive result unavailable", "DRIVE_ERROR");
        }
    }
}
