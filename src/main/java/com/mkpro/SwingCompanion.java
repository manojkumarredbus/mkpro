package com.mkpro;

import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Collections;

public class SwingCompanion {

    private final Runner runner;
    private final Session session;
    private final InMemorySessionService sessionService;
    
    private JFrame frame;
    private JTextArea chatHistory;
    private JTextField inputField;
    private JButton sendButton;
    private JProgressBar progressBar;

    public SwingCompanion(Runner runner, Session session, InMemorySessionService sessionService) {
        this.runner = runner;
        this.session = session;
        this.sessionService = sessionService;
        initializeUI();
    }

    private void initializeUI() {
        // Main Frame
        frame = new JFrame("mkpro Companion");
        frame.setSize(600, 800);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));

        // Chat History Area (Non-editable)
        chatHistory = new JTextArea();
        chatHistory.setEditable(false);
        chatHistory.setLineWrap(true);
        chatHistory.setWrapStyleWord(true);
        chatHistory.setFont(new Font("Consolas", Font.PLAIN, 14));
        chatHistory.setMargin(new Insets(10, 10, 10, 10));
        
        // Auto-scroll to bottom
        DefaultCaret caret = (DefaultCaret) chatHistory.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(chatHistory);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        frame.add(scrollPane, BorderLayout.CENTER);

        // Input Panel
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        inputField = new JTextField();
        inputField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        inputField.addActionListener(this::sendMessage); // Enter key triggers send

        sendButton = new JButton("Send");
        sendButton.addActionListener(this::sendMessage);

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        
        // Progress Bar (Indeterminate)
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(100, 4));
        inputPanel.add(progressBar, BorderLayout.NORTH);

        frame.add(inputPanel, BorderLayout.SOUTH);

        // Welcome Message
        appendToHistory("System", "mkpro Companion ready. Type your message below.");
    }

    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    private void sendMessage(ActionEvent e) {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        inputField.setText("");
        inputField.setEnabled(false);
        sendButton.setEnabled(false);
        progressBar.setVisible(true);

        appendToHistory("User", text);

        Content content = Content.builder()
                .role("user")
                .parts(Collections.singletonList(Part.builder().text(text).build()))
                .build();

        // Run agent asynchronously
        Disposable d = runner.runAsync("user", session.id(), content)
                .observeOn(Schedulers.io()) // Process on IO thread
                .filter(event -> event.content().isPresent())
                .subscribe(
                        event -> {
                            event.content()
                                .flatMap(Content::parts)
                                .orElse(Collections.emptyList())
                                .forEach(part -> 
                                    part.text().ifPresent(chunk -> 
                                        SwingUtilities.invokeLater(() -> appendText(chunk))
                                    )
                                );
                        },
                        error -> {
                            SwingUtilities.invokeLater(() -> {
                                appendToHistory("Error", error.getMessage());
                                resetInput();
                            });
                            error.printStackTrace();
                        },
                        () -> {
                            SwingUtilities.invokeLater(() -> {
                                appendText("\n\n"); // End of message spacing
                                resetInput();
                            });
                        }
                );
    }

    private void resetInput() {
        inputField.setEnabled(true);
        sendButton.setEnabled(true);
        progressBar.setVisible(false);
        inputField.requestFocusInWindow();
    }

    private void appendToHistory(String role, String message) {
        chatHistory.append("\n[" + role + "]: " + message + "\n");
    }
    
    private void appendText(String text) {
        chatHistory.append(text);
    }
}
