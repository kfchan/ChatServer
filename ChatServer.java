import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChatServer {

	private static String THIS_IS_YOU = "(** this is you!)";

	private HashMap<String,Socket> socks; // list of sockets for the chatroom
	private Lock lockSocks; // for the list of sockets
	private Lock lockChatrooms;// for the list of people in each chatroom
	private HashMap<String, HashSet<String>> chatrooms; // chatroom and chatroom members
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
		lockSocks = new ReentrantLock();
		lockChatrooms = new ReentrantLock();
		chatrooms = new HashMap<String, HashSet<String>>(); 

		// default to one chatroom for now to make sure it works
		chatrooms.put("main", new HashSet<String>());
		chatrooms.put("cats", new HashSet<String>());

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
					System.err.println("Error accepting connection.");
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
			System.err.println("Creating socket failed.");
			System.exit(1);
		} catch (IllegalArgumentException e) {
			System.err.println("Error binding to port.");
			System.exit(1);
		} 
	}

	/**
	* handles looping message sending for each client
	* starts with asking user for their name
	**/
	public void handle_client(Socket sock) throws IOException {
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

		String username = getUsername(in, out, sock);

		// lets the user join or create chatrooms
		// or see a menu with command options or quit
		commands(username, in, out, sock);
	}

	private void commands(String username, InputStream in, OutputStream out, Socket sock) throws IOException {
		byte[] data = new byte[2000];
		int len = 0;

		String listOfCommands = "<= Here are a list of commands you can do! \n";
		listOfCommands += "<= * /join <Room Name>: lets you join the room called \'Room Name\' \n";
		listOfCommands += "<= * /rooms: prints out the list of rooms and how many people are in each \n";
		listOfCommands += "<= * /createRoom <Room Name>: creates a chatroom called \'Room Name\' \n";
		listOfCommands += "<= * /deleteRoom <Room Name>: deletes the chatroom called \'Room Name\' \n";
		listOfCommands += "<= * /help <Room Name>: lists these command options \n";
		listOfCommands += "<= * /quit: to exit the chatroom \n";
		listOfCommands += "<= End of list. \n";

		out.write(listOfCommands.getBytes());

		String user = "=> ";

		out.write(user.getBytes());

		while ((len = in.read(data)) != -1) {
			String message = new String(data, 0, len);

			String[] cmd = message.substring(0, message.length()-2).split(" ");
			if (cmd.length == 0) { // user didnt input anything; just continue
				continue;
			}

			if (cmd[0].equals("/quit")) {
				lockSocks.lock();
				socks.remove(username);
				lockSocks.unlock();			
				sock.close();
				return;
			} else if (cmd[0].equals("/rooms")) {
				printRooms(sock);
			} else if (cmd[0].equals("/join")) {
				// default group for now
				if (cmd.length < 2) {
					String incorrectArgs = "<= Please specify a chatroom name after \'/join\'. \n";
					incorrectArgs += user;
					out.write(incorrectArgs.getBytes());
					continue;
				}

				String groupName = cmd[1];
				if (!chatrooms.containsKey(groupName)) {
					String noGroup = "<= That is not an available chatroom name. \n";
					noGroup += "<= Please try \'/rooms\' for a list of available rooms. \n";
					noGroup += user;
					out.write(noGroup.getBytes());
					continue;
				}

				newUserToGroup(username, groupName, sock);
				chat(groupName, username, in, out, sock);
			} else if (cmd[0].equals("/help")) {
				out.write(listOfCommands.getBytes());
			} else if (cmd[0].equals("/createRoom")) {
				if (cmd.length < 2) {
					String incorrectArgs = "<= Please specify a chatroom name after \'/createRoom\'. \n";
					incorrectArgs += user;
					out.write(incorrectArgs.getBytes());
					continue;
				}

				String groupName = cmd[1];
				lockChatrooms.lock();
				chatrooms.put(groupName, new HashSet<String>());
				lockChatrooms.unlock();

				String created = "<= " + groupName + " created. \n";
				out.write(created.getBytes());
			} else if (cmd[0].equals("/deleteRoom")){
				if (cmd.length < 2) {
					String incorrectArgs = "<= Please specify a chatroom name after \'/deleteRoom\'. \n";
					incorrectArgs += user;
					out.write(incorrectArgs.getBytes());
					continue;
				}

				String groupName = cmd[1];
				lockChatrooms.lock();
				if (!chatrooms.containsKey(groupName)) {
					String noRoom = "<= There is no room called " + groupName + " found. \n";
					noRoom += user;
					out.write(noRoom.getBytes());
					lockChatrooms.unlock();
					continue;
				}

				HashSet<String> members = chatrooms.get(groupName);
				if (members.size() != 0) {
					String noDelete = "<= You can't delete a room with people still in it! \n";
					noDelete += user;
					out.write(noDelete.getBytes());
					lockChatrooms.unlock();
					continue;
				}

				chatrooms.remove(groupName);
				lockChatrooms.unlock();

				String deleted = "<= " + groupName + " deleted. \n";
				out.write(deleted.getBytes());
			}else { // error - let the user know the list of commands!
				String error = "Whoops! That wasn't a valid command.. try typing \'/help\' for a list of commands!";
				out.write(error.getBytes());
			}

			out.write(user.getBytes());
		}
		if (len == -1) { // user left - take them off the socks list
			lockSocks.lock();
			socks.remove(username);
			lockSocks.unlock();
			sock.close();
			return;
		}
	}

	private void printRooms(Socket sock) throws IOException {
		lockChatrooms.lock();
		if (chatrooms.size() == 0) {
			String noRooms = "<= There are no chatrooms open right now! \n";
			noRooms += "<= You can create one by using the \'/createRoom <Room Name>\' command. \n";
			sock.getOutputStream().write(noRooms.getBytes());
			lockChatrooms.unlock();
			return;
		}

		String rooms = "<= Active rooms are: \n";
		for (String chatroomName : chatrooms.keySet()) {
			HashSet<String> members = chatrooms.get(chatroomName);
			rooms += "<= * " + chatroomName + " (" + members.size() + ") \n";
		}
		lockChatrooms.unlock();
		sock.getOutputStream().write(rooms.getBytes());
	}

	/**
	* allows the user to chat in the specified chat room
	**/ 
	private void chat(String groupName, String username, InputStream in, OutputStream out, Socket sock) throws IOException {
		byte[] data = new byte[2000];
		int len = 0;

		while ((len = in.read(data)) != -1) {
			String message = new String(data, 0, len);

			if (message.contains("/leave")) { // user to leave the chatroom - remove from chatroom list
				String leftRoom = "<= * user has left chat: " + username;
				sendMessageToChatroom(groupName, leftRoom, username);
				lockChatrooms.lock();
				HashSet members = chatrooms.get(groupName);
				members.remove(username);
				lockChatrooms.unlock();
				return;
			}

			// print message to all other users
			sendMessage(groupName, "<= " + username + ": " + message, username);
		}
		if (len == -1) { // if user leaves server remove them from all lists and close the socket
			String leftRoom = "<= * user has left chat: " + username;
			sendMessageToChatroom(groupName, leftRoom, username);
			lockChatrooms.lock();
			HashSet members = chatrooms.get(groupName);
			members.remove(username);
			lockChatrooms.unlock();
			lockSocks.lock();
			socks.remove(username);
			lockSocks.unlock();			
			sock.close();

			sock.close();
			return;
		}
	}

	/**
	* gets the username
	**/ 
	private String getUsername(InputStream in, OutputStream out, Socket sock) throws IOException {
		byte[] data = new byte[2000];
		int len = 0;

		String greeting = "<= Welcome to Katherine's chat server! \n";
		greeting += "<= Login Name? \n";
		greeting += "=> ";
		out.write(greeting.getBytes());

		String username = "";
		boolean loop = false;

		while ((len = in.read(data)) != -1) {
			username = new String(data, 0, len-2);
			lockSocks.lock();
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
			lockSocks.unlock();

			if (!loop) { // valid username
				String customWelcome = "<= Welcome " + username + "!\n";
				out.write(customWelcome.getBytes());
				break;
			}
		}
		if (len == -1) { // user left; close the socket			
			sock.close();
			return "";
		}
		return username;
	}

	/**
	* handles new user functions when they join a chat room
	**/
	private void newUserToGroup(String username, String groupName, Socket newSock) {
		lockChatrooms.lock();
		HashSet<String> members = chatrooms.get(groupName);
		members.add(username);
		lockChatrooms.unlock();

		StringBuffer message = new StringBuffer();
		message.append("<= Entering room: ");
		message.append(username);
		message.append("\n");
		byte[] m = message.toString().getBytes();
		sendMessage(groupName, message.toString(), username);

		StringBuffer users = new StringBuffer();
		users.append("<= Current users online: \n");

		Socket s;
		OutputStream out;
		lockChatrooms.lock();
		lockSocks.lock();
		Iterator<String> it = members.iterator();
		while (it.hasNext()) {
			String n = it.next();
			s = socks.get(n);
			users.append("<= * ");
			users.append(n);
			users.append(" ");
			if (s == newSock) {
				users.append(THIS_IS_YOU);
			}
			users.append("\n");
		}
		lockSocks.unlock();
		lockChatrooms.unlock();
		users.append("<= End of list. \n");

		try {
			newSock.getOutputStream().write(users.toString().getBytes());
		} catch (IOException e) {
			System.err.println("Error: message sending failed for: " + username);
		}
	}

	/**
	* sends a message to everyone but the sender's room for now
	* will be changing it so that the server can handle multiple chat rooms
	**/
	private void sendMessage(String groupName, String message, String username) {
		byte[] m = message.getBytes();
		OutputStream out;
		Socket s;
		boolean skip = false;

		lockChatrooms.lock();
		lockSocks.lock();
		HashSet<String> members = chatrooms.get(groupName);
		Iterator<String> it = members.iterator();
		while (it.hasNext()) {
			String n = it.next();
			if (n.equals(username)) {
				skip = true;
			} 
			if (!skip) {
				try {
					s = socks.get(n);
					out = s.getOutputStream();
					out.write(m);
				} catch (IOException e) {
					System.err.println("Error: message sending failed for: " + n );
					return;
				}
			}
			skip = false;
		}
		lockChatrooms.unlock();
		lockSocks.unlock();
	}

	/**
	* sends a message to everyone
	* for the messages when someone leaves
	**/
	private void sendMessageToChatroom(String groupName, String message, String username) {
		byte[] mToRest = (message + "\n").getBytes();
		byte[] mToSender = (message + " " + THIS_IS_YOU + "\n").getBytes();
		byte[] m = mToRest;
		OutputStream out;
		Socket s;

		lockChatrooms.lock();
		lockSocks.lock();
		HashSet<String> members = chatrooms.get(groupName);
		Iterator<String> it = members.iterator();
		while (it.hasNext()) {
			String n = it.next();
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
		lockChatrooms.unlock();
		lockSocks.unlock();
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