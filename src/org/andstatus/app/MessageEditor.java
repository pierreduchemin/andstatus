/**
 * Copyright (C) 2013 yvolk (Yuri Volkov), http://yurivolkov.com
 * Copyright (C) 2008 Torgny Bjers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andstatus.app;

import org.andstatus.app.account.MyAccount;
import org.andstatus.app.account.MyAccount.CredentialsVerificationStatus;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.data.MyDatabase;
import org.andstatus.app.data.MyProvider;
import org.andstatus.app.data.TimelineTypeEnum;
import org.andstatus.app.net.Connection.ApiRoutineEnum;
import org.andstatus.app.service.CommandData;
import org.andstatus.app.service.CommandEnum;
import org.andstatus.app.service.MyServiceManager;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import org.andstatus.app.context.*;
import org.andstatus.app.util.*;

/**
 * "Enter your message here" box 
 */
class MessageEditor {
    private ActionableMessageList mMessageList;
    private android.view.ViewGroup mEditorView;

    /**
     * Text to be sent
     */
    private EditText mEditText;
    private TextView mCharsLeftText;
    /**
     * Information about the message we are editing
     */
    private TextView mDetails;

    /**
     * Id of the Message to which we are replying
     * -1 - is non-existent id
     */
    private long mReplyToId = -1;
    /**
     * Recipient Id. If =0 we are editing Public message
     */
    private long mRecipientId = 0;

    /**
     * {@link MyAccount} to use with this message (send/reply As ...)
     */
    private MyAccount mAccount = null;
    private boolean mShowAccount = false;

    /**
     * Do we hold loaded but not restored state
     */
    private boolean mIsStateLoaded = false;
    private String statusRestored = "";
    private long replyToIdRestored = 0;
    private long recipientIdRestored = 0;
    private String accountGuidRestored = "";
    private boolean showAccountRestored = false;
    
    public MessageEditor(ActionableMessageList actionableMessageList) {
        mMessageList = actionableMessageList;

        ViewGroup layoutParent = (ViewGroup) mMessageList.getActivity().findViewById(R.id.myLayoutParent);
        LayoutInflater inflater = LayoutInflater.from(mMessageList.getActivity());
        mEditorView = (ViewGroup) inflater.inflate(R.layout.message_editor, null);
        layoutParent.addView(mEditorView);
        
        mCharsLeftText = (TextView) mEditorView.findViewById(R.id.messageEditCharsLeftTextView);
        mDetails = (TextView) mEditorView.findViewById(R.id.messageEditDetails);
        
        setupButtons();
        setupEditText();
        hide();
    }

    private void setupButtons() {
        Button createMessageButton = (Button) mMessageList.getActivity().findViewById(R.id.createMessageButton);
        createMessageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MyAccount accountForButton = accountforCreateMessageButton();
                if ( isVisible() || accountForButton == null) {
                    hide();
                } else {
                    startEditingMessage("", 0, 0, accountForButton, mMessageList.isTimelineCombined());
                }
            }
        });
        
        Button sendButton = (Button) mEditorView.findViewById(R.id.messageEditSendButton);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessageAndCloseEditor();
            }
        });
    }

    private void setupEditText() {
        mEditText = (EditText) mEditorView.findViewById(R.id.messageBodyEditText);
        mEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (mAccount != null) {
                    mCharsLeftText.setText(String.valueOf(mAccount.charactersLeftForMessage(s.toString())));
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Nothing to do
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Nothing to do
            }
        });

        mEditText.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                            sendMessageAndCloseEditor();
                            return true;
                        case KeyEvent.KEYCODE_ENTER:
                            if (event.isAltPressed() || 
							        !MyPreferences.getBoolean(MyPreferences.KEY_ENTER_SENDS_MESSAGE, true)) {
                                mEditText.append("\n");
                                return true;
                            }
                            break;
                        default:
                            mCharsLeftText.setText(String.valueOf(mAccount
                                    .charactersLeftForMessage(mEditText.getText().toString())));
                            break;
                    }
                }
                return false;
            }
        });

        mEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.isAltPressed()) {
                    return false;
                }
                sendMessageAndCloseEditor();
                return true;
            }
        });
    }
    
    private MyAccount accountforCreateMessageButton() {
        MyAccount accountForButton = null;
        if (isVisible()) {
            accountForButton = mAccount;
        } else {
            accountForButton = MyContextHolder.get().persistentAccounts().getCurrentAccount();
            if (accountForButton != null 
                    && accountForButton.getCredentialsVerified() != MyAccount.CredentialsVerificationStatus.SUCCEEDED ) {
                accountForButton = null;
            }
        }
        return accountForButton;
    }
    
    /**
     * Continue message editing
     * @return new state of visibility
     */
    public boolean toggleVisibility() {
        boolean isVisibleNew = !isVisible();
        if (isVisibleNew) {
            show();
        } else {
            hide();
        }
        return isVisibleNew;
    }

    public void show() {
        mCharsLeftText.setText(String.valueOf(mAccount
                .charactersLeftForMessage(mEditText.getText().toString())));
        
        mEditorView.setVisibility(View.VISIBLE);
        updateCreateMessageButton();
        
        mEditText.requestFocus();
        if (!isHardwareKeyboardAttached()) {
            openSoftKeyboard();
        }
    }
    
    private boolean isHardwareKeyboardAttached() {
        Configuration c = mMessageList.getActivity().getResources().getConfiguration();
        switch (c.keyboard) {
            case Configuration.KEYBOARD_12KEY:
            case Configuration.KEYBOARD_QWERTY:
                return true;
            default:
                return false;
        }
    }

    private void openSoftKeyboard() {
        InputMethodManager inputMethodManager = (InputMethodManager) mMessageList.getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.toggleSoftInputFromWindow(mEditText.getWindowToken(), InputMethodManager.SHOW_FORCED, 0);        
    }
    
    public void updateCreateMessageButton() {
        MyAccount accountForButton = accountforCreateMessageButton();
        boolean isButtonVisible = isVisible();
        int resId = R.string.button_hide;
        if (!isButtonVisible
                && accountForButton != null 
                && mMessageList.getTimelineType() != TimelineTypeEnum.DIRECT
                && mMessageList.getTimelineType() != TimelineTypeEnum.MESSAGESTOACT
                && accountForButton.getCredentialsVerified() == CredentialsVerificationStatus.SUCCEEDED ) {
            isButtonVisible = true;
            resId = accountForButton.alternativeTermForResourceId(R.string.button_create_message);
        }
        Button createMessageButton = (Button) mMessageList.getActivity().findViewById(R.id.createMessageButton);
        createMessageButton.setText(resId);
        createMessageButton.setVisibility(isButtonVisible ? View.VISIBLE : View.GONE);
    }
    
    public void hide() {
        mEditorView.setVisibility(View.GONE);
        closeSoftKeyboard();
        updateCreateMessageButton();
    }

    private void closeSoftKeyboard() {
        InputMethodManager inputMethodManager = (InputMethodManager) mMessageList.getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
    }
    
    public boolean isVisible() {
        return mEditorView.getVisibility() == View.VISIBLE;
    }
    
    /**
     * Start editing "Status update" (public message) OR "Direct message".
     * If both replyId and recipientId parameters are the same, we continue editing 
     * (i.e. previous not sent message is preserved). This behavior is close to how 
     * the application worked before.
     * @param textInitial not null String
     * @param replyToId =0 if not replying
     * @param recipientId =0 if this is Public message
     */
    public void startEditingMessage(String textInitial, long replyToId, long recipientId, MyAccount myAccount, boolean showAccount) {
        if (myAccount == null) {
            return;
        }
        String accountGuidPrev = "";
        if (mAccount != null) {
            accountGuidPrev = mAccount.getAccountName();
        }
        if (mReplyToId != replyToId || mRecipientId != recipientId 
                || accountGuidPrev.compareTo(myAccount.getAccountName()) != 0 || mShowAccount != showAccount) {
            mReplyToId = replyToId;
            mRecipientId = recipientId;
            mAccount = myAccount;
            mShowAccount = showAccount;
            String textInitial2 = textInitial;
            String messageDetails = showAccount ? mAccount.getAccountName() : "";
            if (recipientId == 0) {
                if (replyToId != 0) {
                    String replyToName = MyProvider.msgIdToUsername(MyDatabase.Msg.AUTHOR_ID, replyToId);
                    if (!TextUtils.isEmpty(textInitial2)) {
                        textInitial2 += " ";
                    }
                    textInitial2 = "@" + replyToName + " ";
                    messageDetails += " " + String.format(MyContextHolder.get().getLocale(), MyContextHolder.get().context().getText(R.string.message_source_in_reply_to).toString(), replyToName);
                }
            } else {
                String recipientName = MyProvider.userIdToName(recipientId);
                if (!TextUtils.isEmpty(recipientName)) {
                    messageDetails += " " + String.format(MyContextHolder.get().getLocale(), MyContextHolder.get().context().getText(R.string.message_source_to).toString(), recipientName);
                }
            }
            mEditText.setText(textInitial2);
            // mEditText.append(textInitial, 0, textInitial.length());
            mDetails.setText(messageDetails);
            if (TextUtils.isEmpty(messageDetails)) {
                mDetails.setVisibility(View.GONE);
            } else {
                mDetails.setVisibility(View.VISIBLE);
            }
        }
        
        if (mAccount.getConnection().isApiSupported(ApiRoutineEnum.ACCOUNT_RATE_LIMIT_STATUS)) {
            // Start asynchronous task that will show Rate limit status
            MyServiceManager.sendForegroundCommand(new CommandData(CommandEnum.RATE_LIMIT_STATUS, mAccount.getAccountName()));
        }
        
        show();
    }
    
    private void sendMessageAndCloseEditor() {
        String status = mEditText.getText().toString();
        if (TextUtils.isEmpty(status.trim())) {
            Toast.makeText(mMessageList.getActivity(), R.string.cannot_send_empty_message,
                    Toast.LENGTH_SHORT).show();
        } else if (mAccount.charactersLeftForMessage(status) < 0) {
            Toast.makeText(mMessageList.getActivity(), R.string.message_is_too_long,
                    Toast.LENGTH_SHORT).show();
        } else {
			if (MyPreferences.getBoolean(MyPreferences.KEY_SENDING_MESSAGES_LOG_ENABLED, false)) {
				MyLog.setLogToFile(true);
			}
            CommandData commandData = CommandData.updateStatus(mAccount.getAccountName(), status, mReplyToId, mRecipientId);
            MyServiceManager.sendForegroundCommand(commandData);

            // Let's assume that everything will be Ok
            // so we may clear the text box with the sent message text...
            mReplyToId = 0;
            mRecipientId = 0;
            mEditText.setText("");
            mAccount = null;
            mShowAccount = false;

            hide();
        }
    }
    
    public void saveState(Bundle outState) {
        mIsStateLoaded = false;
        if (outState != null && mEditText != null && mAccount != null) {
            String status = mEditText.getText().toString();
            if (!TextUtils.isEmpty(status)) {
                outState.putString(IntentExtra.EXTRA_MESSAGE_TEXT.key, status);
                outState.putLong(IntentExtra.EXTRA_INREPLYTOID.key, mReplyToId);
                outState.putLong(IntentExtra.EXTRA_RECIPIENTID.key, mRecipientId);
                outState.putString(IntentExtra.EXTRA_ACCOUNT_NAME.key, mAccount.getAccountName());
                outState.putBoolean(IntentExtra.EXTRA_SHOW_ACCOUNT.key, mShowAccount);
            }
        }
    }
    
    public void loadState(Bundle savedInstanceState) {
        if (savedInstanceState != null 
                && savedInstanceState.containsKey(IntentExtra.EXTRA_INREPLYTOID.key) 
                && savedInstanceState.containsKey(IntentExtra.EXTRA_MESSAGE_TEXT.key)) {
            String status = savedInstanceState.getString(IntentExtra.EXTRA_MESSAGE_TEXT.key);
            if (!TextUtils.isEmpty(status)) {
                statusRestored = status;
                replyToIdRestored = savedInstanceState.getLong(IntentExtra.EXTRA_INREPLYTOID.key);
                recipientIdRestored = savedInstanceState.getLong(IntentExtra.EXTRA_RECIPIENTID.key);
                accountGuidRestored = savedInstanceState.getString(IntentExtra.EXTRA_ACCOUNT_NAME.key);
                showAccountRestored = savedInstanceState.getBoolean(IntentExtra.EXTRA_SHOW_ACCOUNT.key);
                mIsStateLoaded = true;
            }
        }
    }
    
    /**
     * Do we hold loaded but not restored state?
     */
    public boolean isStateLoaded() {
        return mIsStateLoaded;
    }
    
    public void continueEditingLoadedState() {
        if (isStateLoaded()) {
            mIsStateLoaded = false;
            startEditingMessage(statusRestored, replyToIdRestored, recipientIdRestored, 
                    MyContextHolder.get().persistentAccounts().fromAccountName(accountGuidRestored), showAccountRestored);
        }
    }
}
