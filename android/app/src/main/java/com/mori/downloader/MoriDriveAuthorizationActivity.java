package com.mori.downloader;

import android.app.Activity;
import android.app.PendingIntent;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import java.util.concurrent.Executors;

/** Google Play services owns the consent/Picker UI. No OAuth pages or tokens enter a WebView. */
public class MoriDriveAuthorizationActivity extends AppCompatActivity {
    private static final int AUTHORIZING = 1, READY_PICKER = 2, WAITING_PICKER = 3,
        VALIDATING = 4, SUCCEEDED = 5, FAILED = 6;
    private AuthorizationState operation;

    public static class AuthorizationState extends ViewModel {
        final MutableLiveData<Integer> phase = new MutableLiveData<>();
        long generation;
        PendingIntent pickerIntent;
        volatile boolean cancelled;

        @Override protected void onCleared() { cancelled = true; }
    }

    private final ActivityResultLauncher<IntentSenderRequest> picker = registerForActivityResult(
        new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (operation == null || operation.cancelled) return;
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                operation.phase.setValue(FAILED);
                return;
            }
            try {
                accept(Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(result.getData()));
            } catch (Exception ignored) { operation.phase.setValue(FAILED); }
        });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            operation = new ViewModelProvider(this).get(AuthorizationState.class);
            if (operation.phase.getValue() == null) {
                operation.generation = state == null ? MoriDriveBackend.get(this).generation() : state.getLong("generation");
                // A retained ViewModel handles configuration changes. A new model with saved state
                // means the process lost the operation: cancel rather than waiting for a stale result.
                if (state != null) {
                    operation.cancelled = true;
                    operation.phase.setValue(FAILED);
                } else authorize();
            }
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() {
                    operation.cancelled = true;
                    operation.phase.setValue(FAILED);
                }
            });
            operation.phase.observe(this, phase -> {
                if (phase == READY_PICKER) {
                    operation.phase.setValue(WAITING_PICKER);
                    try {
                        picker.launch(new IntentSenderRequest.Builder(operation.pickerIntent).build());
                        operation.pickerIntent = null;
                    } catch (Exception ignored) { operation.phase.setValue(FAILED); }
                } else if (phase == SUCCEEDED || phase == FAILED) {
                    setResult(phase == SUCCEEDED ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
                    finish();
                }
            });
        } catch (Exception ignored) { finish(); }
    }

    private void authorize() {
        AuthorizationState current = operation;
        current.phase.setValue(AUTHORIZING);
        try {
            AuthorizationRequest request = MoriDriveBackend.authorizationRequest(null)
                .setPrompt(AuthorizationRequest.Prompt.CONSENT | AuthorizationRequest.Prompt.SELECT_ACCOUNT)
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, "true")
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE, "false")
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES,
                    "application/vnd.google-apps.folder")
                .build();
            Identity.getAuthorizationClient(getApplicationContext()).authorize(request)
                .addOnSuccessListener(result -> {
                    if (current.cancelled) return;
                    if (result.hasResolution() && result.getPendingIntent() != null) {
                        current.pickerIntent = result.getPendingIntent();
                        current.phase.setValue(READY_PICKER);
                    } else validate(getApplicationContext(), current, result);
                })
                .addOnFailureListener(ignored -> {
                    if (!current.cancelled) current.phase.setValue(FAILED);
                });
        } catch (Exception ignored) { current.phase.setValue(FAILED); }
    }

    private void accept(AuthorizationResult result) {
        validate(getApplicationContext(), operation, result);
    }

    private static void validate(android.content.Context context, AuthorizationState current, AuthorizationResult result) {
        current.phase.setValue(VALIDATING);
        java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> {
            boolean success = false;
            try {
                MoriDriveBackend.get(context).connect(result, current.generation, () -> !current.cancelled);
                success = true;
            } catch (Exception ignored) {}
            if (!current.cancelled) current.phase.postValue(success ? SUCCEEDED : FAILED);
            worker.shutdown();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (operation != null) {
            state.putLong("generation", operation.generation);
            state.putInt("phase", operation.phase.getValue() == null ? AUTHORIZING : operation.phase.getValue());
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (operation != null && !isChangingConfigurations()) operation.cancelled = true;
        super.onDestroy();
    }
}
