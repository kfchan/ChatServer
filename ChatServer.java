import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChatServer {

	private static String THIS_IS_YOU = "(** this is you!)";

	private HashMap<String,Socket> socks; // list of sockets for the chatroom
	private Lock lock; // for the list of sockets
	private HashMap<String, HashSet<String>> chatrooms;
	private ServerSocket server_sock;

	public static void main(String[] arg) throws IOException { 
		if (arg.length > 1) {
			System.err.println("You only have to pass in the port number.");
			System.exit(-1);
		}
		int port = 8080;
		if (arg.length == 1) {
			try {
				port = Integer.parseInt(arg[0]);
			}
			catch(NumberFormatException e) {
				System.err.println("Invalid port number.");
				System.exit(-1);
			}
		}

		ChatServer myserver = new ChatServer(port);
	}

	/**
	* minds socket to port
	**/
	public ChatServer(int port) throws IOException {
		socks = new HashMap<String,Socket>();
		lock = new ReentrantLock();
		chatrooms = new HashMap<String, HashSet<String>>(); 

		binding(port);
		createThreads();
	}

	private void createThreads() throws IOException {
		try {
			while (true) {
				try {
					// create thread, run()
					Socket sock = server_sock.accept();
					requestHandler rH = new requestHandler(sock);
					Thread t = new Thread(rH);
					t.start();
				} catch (IOException e) {
					System.err.println("Error accepting connection");
					continue;
				}
			}
		} finally {
			server_sock.close();
		}
	}

	private void binding(int port) {
		try {
			server_sock = new ServerSocket(port);
			server_sock.setReuseAddress(true);
		} catch (IOException e) {
			System.err.println("Creating socket failed");
			System.exit(1);
		} catch (IllegalArgumentException e) {
			System.err.println("Error binding to port");
			System.exit(1);
		} 
	}

	/**
	* handles looping message sending for each client
	* starts with asking user for their name
	**/
	public void handle_client(Socket sock) throws IOException {
		byte[] data = new byte[2000];
		int len = 0;
		InputStream in = null;
		OutputStream out = null;
		StringBuffer name = new StringBuffer();
		try {
			in = sock.getInputStream();
			out = sock.getOutputStream();
		} catch (IOException e) {
			System.err.println("Error: message sending failed.");
			return;
		}

		String greeting = "<= Welcome to the Weeby chat server! \n";
		greeting += "<= Login Name? \n";
		greeting += "=> ";
		out.write(greeting.getBytes());

		String username = "";
		boolean loop = false;

		while ((len = in.read(data)) != -1) {
			username = new String(data, 0, len-2);
			lock.lock();
			if (!socks.containsKey(username)) { // user gave an unused name
				loop = false;
				socks.put(username, sock);
			} else { // user gave a name someone else already chose
				loop = true;
				String tryAgain = "<= That user name has been taken!\n";
				tryAgain += "<= Login Name? \n";
				tryAgain += "=> ";
				out.write(tryAgain.getBytes());
			}
			lock.unlock();

			if (!loop) { // valid username
				String customWelcome = "=> Welcome " + username + "!\n";
				// customWelcome += "=> ";
				out.write(customWelcome.getBytes());
				break;
			}
		}
		if (len == -1) {
			System.err.println("Error: message sending failed for unnamed user");
			return;
		}

		// tell all other users (if there are any) there is a new user
		newUser(username, sock);

		while ((len = in.read(data)) != -1) {
			String message = new String(data, 0, len);

			if (message.equals("^]\n")) {
				System.out.println("when does this even print?");
				System.out.println(username + " left");
			}

			// print message to all other users
			sendMessage("=> " + username + ": " + message, username);
		}
		if (len == -1) {
			String leftRoom = "=> * user has left chat: " + username + " \n";
			sendMessageToAll(leftRoom, username);
			lock.lock();
			socks.remove(username);
			lock.unlock();			
			sock.close();

			sock.close();
			return;
		}
	}

	/**
	* handles new user functions when they join a chat room
	**/
	private void newUser(String name, Socket newSock) {
		StringBuffer message = new StringBuffer();
		message.append("<= Entering room: ");
		message.append(name);
		message.append("\n");
		byte[] m = message.toString().getBytes();

		StringBuffer users = new StringBuffer();
		users.append("<= Current users online: \n");

		Socket s;
		OutputStream out;
		lock.lock();
		for (String n : socks.keySet()) {
			s = socks.get(n);
			users.append("<= * ");
			users.append(n);
			users.append(" ");
			if (s == newSock) {
				users.append(THIS_IS_YOU);
				// continue;
			}
			users.append("\n");
			try {
				out = s.getOutputStream();
				out.write(m);
			} catch (IOException e) {
				System.err.println("Error: message sending failed.");
				return;
			}
		}
		lock.unlock();
		users.append("<= End of list. \n");

		try{
			newSock.getOutputStream().write(users.toString().getBytes());
		} catch (IOException e) {
			System.err.println("Error: message sending failed for: " + name);
		}
	}

	/**
	* sends a message to everyone but the sender's room for now
	* will be changing it so that the server can handle multiple chat rooms
	**/
	private void sendMessage(String message, String username) {
		byte[] m = message.getBytes();
		OutputStream out;
		Socket s;
		lock.lock();
		for (String n : socks.keySet()) {
			if (n.equals(username)) {
				continue;
			} 
			try {
				s = socks.get(n);
				out = s.getOutputStream();
				out.write(m);
			} catch (IOException e) {
				System.err.println("Error: message sending failed for: " + n );
				return;
			}
		}
		lock.unlock();
	}

	/**
	* sends a message to everyone
	* for the messages when someone leaves
	**/
	private void sendMessageToAll(String message, String username) {
		byte[] mToRest = message.getBytes();
		byte[] mToSender = (message + " " + THIS_IS_YOU).getBytes();
		byte[] m = mToRest;
		OutputStream out;
		Socket s;
		lock.lock();
		for (String n : socks.keySet()) {
			try {
				s = socks.get(n);
				out = s.getOutputStream();
				if (n.equals(username)) {
					m = mToSender;
				}
				out.write(m);
				m = mToRest;
			} catch (IOException e) {
				System.err.println("Error: message sending failed for: " + n );
				return;
			}
		}
		lock.unlock();
	}

	/**
	* thread for each socket
	**/
	public class requestHandler implements Runnable {
		Socket socket;

		public requestHandler(Socket socket) {
			this.socket = socket;
		}

		// override run()
		@Override
		public void run() {
			// respond to the client
			try {
				handle_client(socket);
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
	}
}