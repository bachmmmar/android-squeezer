package com.danga.squeezer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class SqueezeService extends Service {
    private static final String TAG = "SqueezeService";
    private static final int PLAYBACKSERVICE_STATUS = 1;
	
    // Incremented once per new connection and given to the Thread
    // that's listening on the socket.  So if it dies and it's not the
    // most recent version, then it's expected.  Else it should notify
    // the server of the disconnection.
    private final AtomicInteger currentConnectionGeneration = new AtomicInteger(0);
	
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final AtomicReference<Socket> socketRef = new AtomicReference<Socket>();
    private final AtomicReference<IServiceCallback> callback = new AtomicReference<IServiceCallback>();
    private final AtomicReference<PrintWriter> socketWriter = new AtomicReference<PrintWriter>();
    private final AtomicReference<String> activePlayerId = new AtomicReference<String>();
    private final AtomicReference<Map<String, String>> knownPlayers = new AtomicReference<Map<String, String>>();
    
    @Override
        public void onCreate() {
    	super.onCreate();
    	
        // Clear leftover notification in case this service previously got killed while playing                                                
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(PLAYBACKSERVICE_STATUS);
    }
	
    @Override
	public IBinder onBind(Intent intent) {
        return squeezeService;
    }
	
    @Override
	public void onDestroy() {
        super.onDestroy();
        disconnect();
        callback.set(null);
    }

    private void disconnect() {
        currentConnectionGeneration.incrementAndGet();
        Socket socket = socketRef.get();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {}
        }
        socketRef.set(null);
        socketWriter.set(null);
        isConnected.set(false);
        isPlaying.set(false);
        knownPlayers.set(null);
        setConnectionState(false);
        clearOngoingNotification();
    }

    private synchronized void sendCommand(String command) {
        PrintWriter writer = socketWriter.get();
        if (writer == null) return;
        Log.v(TAG, "SENDING: " + command);
        writer.println(command);
    }
	
    private void sendPlayerCommand(String command) {
        if (activePlayerId == null) {
            return;
        }
        sendCommand(activePlayerId + " " + command);
    }
	
    private void onLineReceived(String serverLine) {
        Log.v(TAG, "LINE: " + serverLine);
        List<String> tokens = Arrays.asList(serverLine.split(" "));
        if (serverLine.startsWith("players 0 100 count")) {
            parsePlayerList(tokens);
            return;
        }
    }

    private void parsePlayerList(List<String> tokens) {
        Log.v(TAG, "Parsing player list.");
        // TODO: can this block (sqlite lookup via binder call?)  Might want to move it elsewhere.
        final SharedPreferences preferences = getSharedPreferences(Preferences.NAME, MODE_PRIVATE);
    	final String lastConnectedPlayer = preferences.getString(Preferences.KEY_LASTPLAYER, null);
    	Log.v(TAG, "lastConnectedPlayer was: " + lastConnectedPlayer);
        Map<String, String> players = new HashMap<String, String>();
                
        int n = 0;
        int currentPlayerIndex = -1;
        String currentPlayerId = null;
        String currentPlayerName = null;
        String defaultPlayerId = null;
        
        for (String token : tokens) {
            if (++n <= 3) continue;
            int colonPos = token.indexOf("%3A");
            if (colonPos == -1) {
                Log.e(TAG, "Expected colon in playerlist token.");
                return;
            }
            String key = token.substring(0, colonPos);
            String value = decode(token.substring(colonPos + 3));
            Log.v(TAG, "key=" + key + ", value: " + value);
            if ("playerindex".equals(key)) {
                maybeAddPlayerToMap(currentPlayerId, currentPlayerName, players);
                currentPlayerId = null;
                currentPlayerName = null;
                currentPlayerIndex = Integer.parseInt(value);
            } else if ("playerid".equals(key)) {
                currentPlayerId = value;
                if (value.equals(lastConnectedPlayer)) {
                    defaultPlayerId = value;  // Still around, so let's use it.
                }
            } else if ("name".equals(key)) {
                currentPlayerName = value;
            }
        }
        maybeAddPlayerToMap(currentPlayerId, currentPlayerName, players);

        if (defaultPlayerId == null || !players.containsKey(defaultPlayerId)) {
            defaultPlayerId = currentPlayerId;  // arbitrary; last one in list.
        }

        knownPlayers.set(players);
        changeActivePlayer(defaultPlayerId);
    }

    private boolean changeActivePlayer(String playerId) {
        Map<String, String> players = knownPlayers.get();
        if (players == null) {
            Log.v(TAG, "Can't set player; none known.");
            return false;
        }
        if (!players.containsKey(playerId)) {
            Log.v(TAG, "Player " + playerId + " not known.");
            return false;
        }

        Log.v(TAG, "Active player now: " + playerId + ", " + players.get(playerId));
        activePlayerId.set(playerId);

        // TODO: this involves a write and can block (sqlite lookup via binder call), so
        // should be done in an AsyncTask or other thread.
        final SharedPreferences preferences = getSharedPreferences(Preferences.NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();              
        editor.putString(Preferences.KEY_LASTPLAYER, playerId);
        editor.commit();
       
        if (callback.get() != null) {
            try {
                callback.get().onPlayersDiscovered();
                if (playerId != null && players.containsKey(playerId)) {
                    callback.get().onPlayerChanged(
                            playerId, players.get(playerId));
                } else {
                    callback.get().onPlayerChanged("", "");
                }
            } catch (RemoteException e) {
            }
        }
        return true;
    }

    // Add String pair to map if both are non-null and non-empty.    
    private static void maybeAddPlayerToMap(String currentPlayerId,
            String currentPlayerName, Map<String, String> players) {
        if (currentPlayerId != null && !currentPlayerId.equals("") && 
            currentPlayerName != null && !currentPlayerName.equals("")) {
            Log.v(TAG, "Adding player: " + currentPlayerId + ", " + currentPlayerName);
            players.put(currentPlayerId, currentPlayerName);
        }
    }

    private String decode(String substring) {
        try {
            return URLDecoder.decode(substring, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private void startListeningThread() {
        Thread listeningThread = new ListeningThread(socketRef.get(),
                                                     currentConnectionGeneration.incrementAndGet());
        listeningThread.start();

        sendCommand("listen 1");
        // Get info on the first 100 players.
        sendCommand("players 0 100");
    }

    private void setConnectionState(boolean currentState) {
        isConnected.set(currentState);
        if (callback.get() == null) {
            return;
        }
        try {
            Log.d(TAG, "pre-call setting callback connection state to: " + currentState);
            callback.get().onConnectionChanged(currentState);
            Log.d(TAG, "post-call setting callback connection state.");
        } catch (RemoteException e) {
        }
    }
	
    private void setPlayingState(boolean state) {
        isPlaying.set(state);
        if (state) {
            NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification status = new Notification();
            //status.contentView = views;
            PendingIntent pIntent = PendingIntent.getActivity(this, 0,
                                                              new Intent(this, SqueezerActivity.class), 0);
            status.setLatestEventInfo(this, "Music Playing", "Content Text", pIntent);
            status.flags |= Notification.FLAG_ONGOING_EVENT;
            status.icon = R.drawable.stat_notify_musicplayer;
            //status.contentIntent = PendingIntent.getActivity(this, 0,
            //        new Intent(this, SqueezerActivity.class), 0);
            nm.notify(PLAYBACKSERVICE_STATUS, status);
        } else {
            clearOngoingNotification();
        }
		
        if (callback.get() == null) {
            return;
        }
        try {
            callback.get().onPlayStatusChanged(state);
        } catch (RemoteException e) {
        }

    }

    private void clearOngoingNotification() {
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(PLAYBACKSERVICE_STATUS);
    }

    private final ISqueezeService.Stub squeezeService = new ISqueezeService.Stub() {

	    public void registerCallback(IServiceCallback callback) throws RemoteException {
	    	SqueezeService.this.callback.set(callback);
	    	callback.onConnectionChanged(isConnected.get());
	    }
	    
	    public void unregisterCallback(IServiceCallback callback) throws RemoteException {
	    	SqueezeService.this.callback.compareAndSet(callback, null);
	    }

            public int adjustVolumeBy(int delta) throws RemoteException {
                return 0;
            }

            public boolean isConnected() throws RemoteException {
                return isConnected.get();
            }

            public void startConnect(final String hostPort) throws RemoteException {
                int colonPos = hostPort.indexOf(":");
                boolean noPort = colonPos == -1;
                final int port = noPort? 9090 : Integer.parseInt(hostPort.substring(colonPos + 1));
                final String host = noPort ? hostPort : hostPort.substring(0, colonPos);
                executor.execute(new Runnable() {
                        public void run() {
                            SqueezeService.this.disconnect();
                            Socket socket = new Socket();
                            try {
                                socket.connect(
                                               new InetSocketAddress(host, port),
                                               1500 /* ms timeout */);
                                socketRef.set(socket);
                                Log.d(TAG, "Connected to: " + hostPort);
                                socketWriter.set(new PrintWriter(socket.getOutputStream(), true));
                                Log.d(TAG, "writer == " + socketWriter.get());
                                setConnectionState(true);
                                Log.d(TAG, "connection state broadcasted true.");
                                startListeningThread();
                            } catch (SocketTimeoutException e) {
                                Log.e(TAG, "Socket timeout connecting to: " + hostPort);
                            } catch (IOException e) {
                                Log.e(TAG, "IOException connecting to: " + hostPort);
                            }
                        }

                    });
            }

            public void disconnect() throws RemoteException {
                if (!isConnected()) return;
                SqueezeService.this.disconnect();
            }
		
            public boolean togglePausePlay() throws RemoteException {
                if (!isConnected()) {
                    return false;
                }
                Log.v(TAG, "pause...");
                if (isPlaying.get()) {
                    setPlayingState(false);
                    sendPlayerCommand("pause 1");
                } else {
                    setPlayingState(true);
                    // TODO: use 'pause 0 <fade_in_secs>' to fade-in if we knew it was
                    // actually paused (as opposed to not playing at all) 
                    sendPlayerCommand("play");
                }
                Log.v(TAG, "paused.");
                return true;
            }

            public boolean play() throws RemoteException {
                if (!isConnected()) {
                    return false;
                }
                Log.v(TAG, "play..");
                isPlaying.set(true);
                sendPlayerCommand("play");
                Log.v(TAG, "played.");
                return true;
            }

            public boolean stop() throws RemoteException {
                if (!isConnected()) {
                    return false;
                }
                isPlaying.set(false);
                sendPlayerCommand("stop");
                return true;
            }

            public boolean nextTrack() throws RemoteException {
                if (!isConnected() || !isPlaying()) {
                    return false;
                }
                sendPlayerCommand("button jump_fwd");
                return true;
            }
            
            public boolean previousTrack() throws RemoteException {
                if (!isConnected() || !isPlaying()) {
                    return false;
                }
                sendPlayerCommand("button jump_rew");
                return true;
            }
            
            public boolean isPlaying() throws RemoteException {
                return isPlaying.get();
            }

            public boolean getPlayers(List<String> playerIds, List<String> playerNames)
                throws RemoteException {
                Map<String, String> players = knownPlayers.get();
                if (players == null) {
                    return false;
                }
                for (String playerId : players.keySet()) {
                    playerIds.add(playerId);
                    playerNames.add(players.get(playerId));
                }
                return true;
            }

            public boolean setActivePlayer(String playerId) throws RemoteException {
                return changeActivePlayer(playerId);
            }

            public String getActivePlayerId() throws RemoteException {
                String playerId = activePlayerId.get();
                return playerId == null ? "" : playerId;
            }

            public String getActivePlayerName() throws RemoteException {
                String playerId = activePlayerId.get();
                Map<String, String> players = knownPlayers.get();
                if (players == null) {
                    return null;
                }
                return players.get(playerId);
            }
};

    private class ListeningThread extends Thread {
        private final Socket socket;
        private final int generationNumber; 
        public ListeningThread(Socket socket, int generationNumber) {
            this.socket = socket;
            this.generationNumber = generationNumber;
        }
		
        @Override
            public void run() {
            BufferedReader in;
            try {
                in = new BufferedReader(
                                        new InputStreamReader(socket.getInputStream()),
                                        128);
            } catch (IOException e) {
                Log.v(TAG, "IOException while creating BufferedReader: " + e);
                SqueezeService.this.disconnect();
                return;
            }
            IOException exception = null;
            while (true) {
                String line;
                try {
                    line = in.readLine();
                } catch (IOException e) {
                    line = null;
                    exception = e;
                }
                if (line == null) {
                    // Socket disconnected.  This is expected
                    // if we're not the main connection generation anymore,
                    // else we should notify about it.
                    if (currentConnectionGeneration.get() == generationNumber) {
                        Log.v(TAG, "Server disconnected; exception=" + exception);
                        SqueezeService.this.disconnect();
                    } else {
                        // Who cares.
                        Log.v(TAG, "Old generation connection disconnected, as expected.");
                    }
                    return;
                }
                SqueezeService.this.onLineReceived(line);
            }
        }
    }
}
