package com.example.quickchat.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.example.quickchat.adapters.RecentConversationsAdapter;
import com.example.quickchat.databinding.ActivityMainBinding;
import com.example.quickchat.listeners.ConversationListener;
import com.example.quickchat.models.ChatMessage;
import com.example.quickchat.models.User;
import com.example.quickchat.utilities.Constants;
import com.example.quickchat.utilities.PreferenceManager;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends BaseActivity implements ConversationListener {

    private ActivityMainBinding binding;
    private PreferenceManager preferenceManager;
    private List<ChatMessage> conversations;
    private RecentConversationsAdapter conversationsAdapter;
    private FirebaseFirestore database;
    private String recentConversationId;

    private static final int PERMISSION_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        preferenceManager = new PreferenceManager(getApplicationContext());
        init();
        loadUserDetails();
        getToken();
        setListeners();
        listenConversations();
        askPermission();
    }

    private void init() {
        conversations = new ArrayList<>();
        conversationsAdapter = new RecentConversationsAdapter(conversations, this);
        binding.conversationsRecyclerView.setAdapter(conversationsAdapter);
        database = FirebaseFirestore.getInstance();
        recentConversationId = null;
    }

    private void setListeners() {
        binding.imageSignOut.setOnClickListener(v -> signOut());
        binding.fabNewChat.setOnClickListener(v ->
                startActivity(new Intent(getApplicationContext(), UsersActivity.class)));
    }

    private void loadUserDetails() {
        binding.textName.setText(preferenceManager.getString(Constants.KEY_NAME));
        byte[] bytes = Base64.decode(preferenceManager.getString(Constants.KEY_IMAGE), Base64.DEFAULT);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        binding.imageProfile.setImageBitmap(bitmap);
    }

    private void showToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    private  void listenConversations() {
        database.collection(Constants.KEY_COLLECTION_CONVERSATIONS)
                .whereEqualTo(Constants.KEY_SENDER_ID, preferenceManager.getString(Constants.KEY_USER_ID))
                .addSnapshotListener(eventListener);
        database.collection(Constants.KEY_COLLECTION_CONVERSATIONS)
                .whereEqualTo(Constants.KEY_RECEIVER_ID, preferenceManager.getString((Constants.KEY_USER_ID)))
                .addSnapshotListener(eventListener);
    }

    private final EventListener<QuerySnapshot> eventListener = (value, error) -> {
        if (error != null) {
            return;
        }
        if (value != null) {
            database.collection(Constants.KEY_COLLECTION_CONVERSATION_TIME)
                    .document(preferenceManager.getString(Constants.KEY_USER_ID))
                    .get()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            for (DocumentChange documentChange : value.getDocumentChanges()) {
                                if (documentChange.getType() == DocumentChange.Type.ADDED) {
                                    String senderId = documentChange.getDocument().getString(Constants.KEY_SENDER_ID);
                                    String receiverId = documentChange.getDocument().getString(Constants.KEY_RECEIVER_ID);
                                    ChatMessage chatMessage = new ChatMessage();
                                    chatMessage.senderId = senderId;
                                    chatMessage.receiverId = receiverId;
                                    if (preferenceManager.getString(Constants.KEY_USER_ID).equals(senderId)) {
                                        chatMessage.conversationImage = documentChange.getDocument().getString(Constants.KEY_RECEIVER_IMAGE);
                                        chatMessage.conversationName = documentChange.getDocument().getString(Constants.KEY_RECEIVER_NAME);
                                        chatMessage.conversationId = documentChange.getDocument().getString(Constants.KEY_RECEIVER_ID);
                                    }
                                    else {
                                        chatMessage.conversationImage = documentChange.getDocument().getString(Constants.KEY_SENDER_IMAGE);
                                        chatMessage.conversationName = documentChange.getDocument().getString(Constants.KEY_SENDER_NAME);
                                        chatMessage.conversationId = documentChange.getDocument().getString(Constants.KEY_SENDER_ID);
                                    }
                                    chatMessage.message = documentChange.getDocument().getString(Constants.KEY_LAST_MESSAGE);
                                    chatMessage.dateObject = documentChange.getDocument().getDate(Constants.KEY_TIMESTAMP);

                                    DocumentSnapshot documentSnapshot = task.getResult();
                                    chatMessage.lastAccessed = documentSnapshot.getDate(chatMessage.conversationId);

                                    conversations.add(chatMessage);
                                }
                                else if (documentChange.getType() == DocumentChange.Type.MODIFIED) {
                                    for (int i = 0; i < conversations.size(); i++) {
                                        String senderId = documentChange.getDocument().getString(Constants.KEY_SENDER_ID);
                                        String receiverId = documentChange.getDocument().getString(Constants.KEY_RECEIVER_ID);
                                        if (conversations.get(i).senderId.equals(senderId) && conversations.get(i).receiverId.equals(receiverId)) {
                                            conversations.get(i).message = documentChange.getDocument().getString(Constants.KEY_LAST_MESSAGE);
                                            conversations.get(i).dateObject = documentChange.getDocument().getDate(Constants.KEY_TIMESTAMP);
                                            int finalI = i;
                                            DocumentSnapshot documentSnapshot = task.getResult();
                                            conversations.get(finalI).lastAccessed = documentSnapshot.getDate(conversations.get(finalI).conversationId);
                                            break;
                                        }
                                    }
                                }
                            }
                            Collections.sort(conversations, (obj1, obj2) -> obj2.dateObject.compareTo(obj1.dateObject));
                            conversationsAdapter.notifyDataSetChanged();
                            binding.conversationsRecyclerView.smoothScrollToPosition(0);
                            binding.conversationsRecyclerView.setVisibility((View.VISIBLE));
                            binding.progressBar.setVisibility(View.GONE);
                        }
                    });

        }
    };

    private void getToken() {
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(this::updateToken);
    }

    private void updateToken(String token) {
        preferenceManager.putString(Constants.KEY_FCM_TOKEN, token);
        FirebaseFirestore database = FirebaseFirestore.getInstance();
        DocumentReference documentReference =
                database.collection(Constants.KEY_COLLECTION_USERS).document(
                        preferenceManager.getString(Constants.KEY_USER_ID)
                );
        documentReference.update(Constants.KEY_FCM_TOKEN, token)
                .addOnFailureListener(e -> showToast("Unable to update token"));
    }

    private void signOut() {
        showToast("Signing out...");
        FirebaseFirestore database = FirebaseFirestore.getInstance();
        DocumentReference documentReference =
                database.collection(Constants.KEY_COLLECTION_USERS).document(
                        preferenceManager.getString(Constants.KEY_USER_ID)
                );
        HashMap<String, Object> updates = new HashMap<>();
        updates.put(Constants.KEY_FCM_TOKEN, FieldValue.delete());
        documentReference.update(updates)
                .addOnSuccessListener(unused -> {
                    preferenceManager.clear();
                    startActivity(new Intent(getApplicationContext(), SignInActivity.class));
                })
                .addOnFailureListener(e -> showToast("Unable to sign out"));
    }

    @Override
    public void onConversationClicked(User user) {
        for (ChatMessage chatMessage : conversations) {
            if (chatMessage.conversationId == user.id) {
                chatMessage.lastAccessed = new Date();
                database.collection(Constants.KEY_COLLECTION_CONVERSATION_TIME)
                        .document(preferenceManager.getString(Constants.KEY_USER_ID))
                        .update(user.id, new Date())
                        .addOnFailureListener(e -> showToast(e.getMessage()));
            }
        }

        conversationsAdapter.notifyDataSetChanged();

        recentConversationId = user.id;

        Intent intent = new Intent(getApplicationContext(), ChatActivity.class);
        intent.putExtra(Constants.KEY_USER, user);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (recentConversationId == null) {
            return;
        }

        for (ChatMessage chatMessage : conversations) {
            if (chatMessage.conversationId == recentConversationId) {
                chatMessage.lastAccessed = new Date();
                database.collection(Constants.KEY_COLLECTION_CONVERSATION_TIME)
                        .document(preferenceManager.getString(Constants.KEY_USER_ID))
                        .update(recentConversationId, new Date())
                        .addOnFailureListener(e -> showToast(e.getMessage()));
            }
        }

        conversationsAdapter.notifyDataSetChanged();
    }

    public void askPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSION_CODE
            );
        }
    }
}