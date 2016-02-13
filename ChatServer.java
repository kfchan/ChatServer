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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChatServer {

	private HashMap<String,Socket> socks;
	private Lock lock;

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

	public ChatServer(int port) throws IOException {
		socks = new HashMap<String,Socket>();
		lock = new ReentrantLock();

		ServerSocket server_sock = null;

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
		try {
			while (true) {
				try {
					Socket sock = server_sock.accept();
					// create thread, run()
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

		String greeting = "Please enter your name: ";
		out.write(greeting.getBytes());

		String username = "";
		boolean loop = false;

		while ((len = in.read(data)) != -1) {
			username = new String(data, 0, len-2);
			lock.lock();
			if (!socks.containsKey(username)) {
				loop = false;
				socks.put(username, sock);
			} else {
				loop = true;
				String tryAgain = "That user name has been taken! Input another one! \n";
				out.write(tryAgain.getBytes());
			}
			lock.unlock();

			if (!loop) {
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
				System.out.println(username + "left");
			}

			// print message to all other users
			sendMessage(username + ": " + message);
		}
		if (len == -1) {
			lock.lock();
			socks.remove(username);
			lock.unlock();
			String leftRoom = username + " left the chatroom. \n";
			sendMessage(leftRoom);
			sock.close();

			sock.close();
			return;
		}
	}

	private void newUser(String name, Socket newSock) {
		StringBuffer message = new StringBuffer();
		String joined = "joined the chat!";
		message.append(name);
		message.append(" ");
		message.append(joined);
		message.append("\n");
		byte[] m = message.toString().getBytes();

		StringBuffer users = new StringBuffer();
		users.append("Current users online: ");

		Socket s;
		OutputStream out;
		lock.lock();
		for (String n : socks.keySet()) {
			s = socks.get(n);
			if (s == newSock) {
				continue;
			}
			users.append(n);
			users.append(" ");
			try {
				out = s.getOutputStream();
				out.write(m);
			} catch (IOException e) {
				System.err.println("Error: message sending failed.");
				return;
			}
		}
		lock.unlock();
		users.append("\n");

		try{
			newSock.getOutputStream().write(users.toString().getBytes());
		} catch (IOException e) {
			System.err.println("Error: message sending failed for: " + name);
		}
	}


	private void sendMessage(String message) {
		byte[] m = message.getBytes();
		OutputStream out;
		Socket s;
		lock.lock();
		for (String n : socks.keySet()) {
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