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
	public static final int DEFAULT_PORT = 5555;
	private static final String THIS_IS_YOU = "(** this is you!)";
	private static final String ARROW = ">> ";
	
	private Lock lockSocks; // for the list of sockets
	private Lock lockChatrooms; // for the list of people in each chatroom
	private Lock lockReplies; // for the list of users to reply to
	private HashMap<String,Socket> socks; // list of sockets for the chatroom
	private HashMap<String, HashSet<String>> chatrooms; // chatroom and chatroom members
	private HashMap<String, String> replyTo; // keeps track of who to reply to for each user
	private ServerSocket server_sock;
	private boolean nameChangeFail;

	/**
	* binds socket to port
	**/
	public ChatServer(int port) {
		lockSocks = new ReentrantLock();
		lockChatrooms = new ReentrantLock();
		lockReplies = new ReentrantLock();
		socks = new HashMap<String,Socket>();
		chatrooms = new HashMap<String, HashSet<String>>(); 
		replyTo = new HashMap<String, String>();
		nameChangeFail = false;

		// auto create one chatroom so that the first user doesn't have to
		chatrooms.put("main", new HashSet<String>());

		binding(port);
		try {
			createThreads();
		} catch (IOException e) {
			System.err.println("Error creating threads for new clients");
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

	/**
	* allows the user to use the given list of commands to do various tasks in the chatroom
	*/
	private void commands(String username, InputStream in, OutputStream out, Socket sock) throws IOException {
		byte[] data = new byte[2000];
		int len = 0;

		String listOfCommands = ARROW + "Here are a list of commands you can do! \n";
		listOfCommands += ARROW + "* /join <Room Name>: lets you join the room called \'Room Name\' \n";
		listOfCommands += ARROW + "* /rooms: prints out the list of rooms and how many people are in each \n";
		listOfCommands += ARROW + "* /createRoom <Room Name>: creates a chatroom called \'Room Name\' \n";
		listOfCommands += ARROW + "* /deleteRoom <Room Name>: deletes the chatroom called \'Room Name\' \n";
		listOfCommands += ARROW + "* /changeUsername <Username>: changes your username to \'Username\' \n";
		listOfCommands += ARROW + "The following commands can also be run within chatrooms: \n";
		listOfCommands += ARROW + "* /users: prints out the list of users are online \n";				
		listOfCommands += ARROW + "* /PM <Username>: to privately message user, \'Username\' \n";
		listOfCommands += ARROW + "* /replyPM <Message>: private message last user who you last sent/recieved \n";
		listOfCommands += ARROW + "  PM with \'Message\' \n";
		listOfCommands += ARROW + "* /help <Room Name>: lists these command options \n";
		listOfCommands += ARROW + "* /quit: to exit the chat server \n";
		listOfCommands += ARROW + "End of list. \n";

		try {
			out.write(listOfCommands.getBytes());
			out.write(ARROW.getBytes());
		} catch (IOException e) {
			System.err.println("Printing out commands for " + username + " failed");
		}

		while ((len = in.read(data)) != -1) {
			String message = new String(data, 0, len);

			String[] cmd = message.substring(0, message.length()-2).split(" ");
			String print = "";
			if (cmd.length == 0) { // user didnt input anything; just continue
				continue;
			}
			if (cmd[0].equals("/join")) {
				print = join(cmd, username, sock);
				if (print.equals("")) {
					return;
				} else if (print.equals(" ")) {
					print = "";
				}
			} else if (cmd[0].equals("/rooms")) {
				print = printRooms(sock);
			} else if (cmd[0].equals("/createRoom")) {
				print = createRoom(cmd, username);
			} else if (cmd[0].equals("/deleteRoom")) {
				print = deleteRoom(cmd, username);
			} else if (cmd[0].equals("/changeUsername")) {
				print = changeUsername(cmd, username);
				if (!nameChangeFail) {
					username = print;
					print = ARROW + "Name has been changed to: " + print + "\n";
					nameChangeFail = false;
				}
			} else if (cmd[0].equals("/users")) {
				print = printUsers(username, sock);
			} else if (cmd[0].equals("/PM")) {
				print = privateMessage(cmd, username, out);
			} else if (cmd[0].equals("/replyPM")) {
				print = replyPM(cmd, username, out);
			} else if (cmd[0].equals("/help")) {
				print = listOfCommands;
			} else if (cmd[0].equals("/quit")) {
				quit(username, out);
				return;
			} else { // error - let the user know the list of commands!
				print = ARROW + "Whoops! That wasn't a valid command.. try typing \'/help\' for a list of commands! \n";
			}

			try { 
				out.write(print.getBytes());
				out.write(ARROW.getBytes());
			} catch (IOException e) {
				System.err.println("Problem printing response to " + username + "'s command request");
			}
		}
		if (len == -1) { // user left - take them off the all lists
			removeFromReplies(username);
			removeFromSocks(username);
			return;
		}
	}

	/**
	* turns the rest of the command array into a string
	*/
	private String getRestOfCommand(String[] command) {
		// we can assume that command.length > 1
		String rtn = "";
		for (int i = 1; i < command.length; i++) {
			rtn += command[i];
			if (i != command.length - 1) {
				rtn += " ";
			}
		}
		return rtn;
	}

	/**
	* gets the username
	**/ 
	private String getUsername(InputStream in, OutputStream out, Socket sock) throws IOException {
		byte[] data = new byte[2000];
		int len = 0;

		String greeting = ARROW + "Welcome to Katherine's chat server! \n";
		greeting += ARROW + "What would you like your Username to be?\n";
		greeting += ARROW;

		try {
			out.write(greeting.getBytes());
		} catch (IOException e) {
			System.err.println("Error printing out the welcome message to new user");
		}

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
				String tryAgain = ARROW + "That user name has been taken!\n";
				tryAgain += ARROW + "Username? \n";
				tryAgain += ARROW;
				try {
					out.write(tryAgain.getBytes());
				} catch (IOException e) {
					System.err.println("Error prompting user pick a username");
				}
			}
			lockSocks.unlock();

			if (!loop) { // valid username
				String customWelcome = ARROW + "Welcome " + username + "!\n";
				try {
					out.write(customWelcome.getBytes());
				} catch (IOException e) {
					System.err.println("Error welcoming " + username + " to the chat server");
				}
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
	* handles the /join command
	* returns empty string if user has left the chat server (and not just the chatromm)
	**/
	private String join(String[] cmd, String username, Socket sock) {
		if (cmd.length < 2) {
			String incorrectArgs = ARROW + "Please specify a chatroom name after \'/join\'. \n";
			return incorrectArgs;
		}

		String groupName = getRestOfCommand(cmd);
		if (!chatrooms.containsKey(groupName)) {
			String noGroup = ARROW + "That is not an available chatroom name. \n";
			noGroup += ARROW + "Please try \'/rooms\' for a list of available rooms. \n";
			return noGroup;
		}

		try {
			newUserToGroup(username, groupName, sock);
			if (chat(groupName, username, sock.getInputStream(), sock.getOutputStream(), sock) == -1) {
				return "";
			}
		} catch (IOException e) {
			System.err.println("Error geting " + username + "'s input or output stream");
		}
		return " ";
	}	

	/**
	* takes care of the /createRoom command
	**/
	private String createRoom(String[] cmd, String username) {
		if (cmd.length < 2) {
			String incorrectArgs = ARROW + "Please specify a chatroom name after \'/createRoom\'. \n";
			return incorrectArgs;
		}

		String groupName = getRestOfCommand(cmd);
		lockChatrooms.lock();
		chatrooms.put(groupName, new HashSet<String>());
		lockChatrooms.unlock();

		String created = ARROW + groupName + " created. \n";
		return created;
	}

	/**
	* takes care of the /deleteRoom command
	**/ 
	private String deleteRoom(String[] cmd, String username) {
		if (cmd.length < 2) {
			String incorrectArgs = ARROW + "Please specify a chatroom name after \'/deleteRoom\'. \n";
			return incorrectArgs;
		}

		String groupName = getRestOfCommand(cmd);
		lockChatrooms.lock();
		if (!chatrooms.containsKey(groupName)) {
			String noRoom = ARROW + "There is no room called " + groupName + " found. \n";
			lockChatrooms.unlock();
			return noRoom;
		}

		HashSet<String> members = chatrooms.get(groupName);
		if (members.size() != 0) {
			String noDelete = ARROW + "You can't delete a room with people still in it! \n";
			lockChatrooms.unlock();
			return noDelete;
		}

		chatrooms.remove(groupName);
		lockChatrooms.unlock();

		String deleted = ARROW + groupName + " deleted. \n";
		return deleted;
	}	

	/**
	* gets and returns the desired name
	**/
	private String changeUsername(String[] cmd, String username) {
		if (cmd.length < 2) {
			String incorrectArgs = ARROW + "Please specify a username you want to change to after \'/changeUsername\'. \n";
			nameChangeFail = true;
			return incorrectArgs;
		} 

		String desiredName = getRestOfCommand(cmd);
		if (socks.containsKey(desiredName)) {
			String takenName = ARROW + "That name has already been taken! Please choose another. \n";
			nameChangeFail = true;
			return takenName;
		}

		nameChangeFail = false;
		changeUsername(username, desiredName);
		return desiredName;
	}

	/** 
	* does the username change for the socks and the replyTo hashmaps
	*/
	private void changeUsername(String currentName, String desiredName) {
		lockReplies.lock();
		String value = replyTo.get(currentName);
		replyTo.remove(currentName);
		replyTo.put(desiredName, value);
		if (replyTo.containsValue(currentName)) {
			for (String key: replyTo.keySet()) {
				if (replyTo.get(key).equals(currentName)) {
					replyTo.put(key, desiredName);
					break;
				}
			}
		}
		lockReplies.unlock();

		lockSocks.lock();
		Socket sock = socks.get(currentName);
		socks.remove(currentName);
		socks.put(desiredName, sock);
		lockSocks.unlock();
	}	

	/**
	* handles new user functions when they join a chat room
	**/
	private void newUserToGroup(String username, String groupName, Socket newSock) {
		String welcome = ARROW + "Welcome to " + groupName + "!\n" + ARROW;
		try {
			newSock.getOutputStream().write(welcome.getBytes());
		} catch (IOException e) {
			System.err.println("Error welcoming " + username + " to " + groupName);
		}

		lockChatrooms.lock();
		HashSet<String> members = chatrooms.get(groupName);
		members.add(username);
		lockChatrooms.unlock();

		// tell everyone in the chatroom that new user has entered
		String message = "Entering room: " + username + "\n";
		sendMessage(groupName, message, username);

		String users = "Current users online: \n";

		Socket s;
		OutputStream out;
		lockChatrooms.lock();
		lockSocks.lock();
		Iterator<String> it = members.iterator();
		while (it.hasNext()) {
			String n = it.next();
			s = socks.get(n);
			users += ARROW + "* " + n + " ";
			if (s == newSock) {
				users += THIS_IS_YOU;
			}
			users += "\n";
		}
		lockSocks.unlock();
		lockChatrooms.unlock();
		users += ARROW + "End of list. \n" + ARROW;

		try {
			newSock.getOutputStream().write(users.getBytes());
		} catch (IOException e) {
			System.err.println("Error: message sending failed for: " + username);
		}
	}

	/**
	* allows the user to chat in the specified chat room
	**/ 
	private int chat(String groupName, String username, InputStream in, OutputStream out, Socket sock) throws IOException {
		String help = "You can use the following commands in the chatroom: \n";
		help += ARROW + "* /leave: to leave the chatroom \n";
		help += ARROW + "* /users: prints out the list of users are online \n";				
		help += ARROW + "* /PM <Username>: to privately message user, \'Username\' \n";
		help += ARROW + "* /replyPM <Message>: private message last user who you last sent/recieved \n";
		help += ARROW + "  PM with \'Message\' \n";
		help += ARROW + "* /help <Room Name>: lists these command options \n";
		help += ARROW + "* /quit: to exit the chat server \n";
		help += ARROW + "End of list. \n";
		
		try {
			out.write(help.getBytes());
			out.write(ARROW.getBytes());
		} catch (IOException e) {
			System.err.println("Printing chatroom commands for " + username + " in group " + groupName + "failed");
		}
		
		byte[] data = new byte[2000];
		int len = 0;

		while ((len = in.read(data)) != -1) {
			String message = new String(data, 0, len);

			String[] cmd = message.substring(0, message.length()-2).split(" ");
			String print = "";
			boolean needArrow = true;
			if (cmd[0].equals("/leave")) { // user to leave the chatroom - remove from chatroom list
				String leftRoom = "* user has left the chatroom: " + username;
				sendMessageToChatroom(groupName, leftRoom, username);
				lockChatrooms.lock();
				HashSet members = chatrooms.get(groupName);
				members.remove(username);
				lockChatrooms.unlock();
				return 0;
			} else if (cmd[0].equals("/PM")) {
				print = privateMessage(cmd, username, out);
			} else if (cmd[0].equals("/replyPM")) {
				print = replyPM(cmd, username, out);
			} else if (cmd[0].equals("/users")) {
				print = printUsers(username, sock);
			} else if (cmd[0].equals("/quit")) {
				String leftRoom = "* user has left the chatroom: " + username;
				sendMessageToChatroom(groupName, leftRoom, username);
				lockChatrooms.lock();
				HashSet members = chatrooms.get(groupName);
				members.remove(username);
				lockChatrooms.unlock();				
				quit(username, out);
				return -1;
			} else if (cmd[0].equals("/help")) {
				print = ARROW + help;
			} else { // a normal message to the members of the chatroom
				out.write(ARROW.getBytes());
				sendMessage(groupName, username + ": " + message, username);
				needArrow = false;
			}

			try {
				out.write(print.getBytes());
				if (needArrow) out.write(ARROW.getBytes());
			} catch (IOException e) {
				System.err.println("Printing the arrow for user " + username + " failed");
			}
		}
		if (len == -1) { // if user leaves server remove them from all lists and close the socket
			String leftRoom = ARROW + "* user has left chat: " + username;
			sendMessageToChatroom(groupName, leftRoom, username);
			lockChatrooms.lock();
			HashSet members = chatrooms.get(groupName);
			members.remove(username);
			lockChatrooms.unlock();
			
			removeFromSocks(username);
			return -1;
		}
		return 0;
	}

	/**
	* sends a message to everyone but the sender's room for now
	**/
	private void sendMessage(String groupName, String message, String username) {
		byte[] m = (message + ARROW).getBytes();
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
				out.write(m);
			} catch (IOException e) {
				System.err.println("Message sending failed for " + n);
					lockChatrooms.unlock();
					lockSocks.unlock();
				return;
			}
		}
		lockChatrooms.unlock();
		lockSocks.unlock();
	}

	/**
	* sends a message to everyone
	* for the messages when someone leaves
	**/
	private void sendMessageToChatroom(String groupName, String message, String username) {
		byte[] mToRest = (message + "\n" + ARROW).getBytes();
		byte[] mToSender = (ARROW + message + " " + THIS_IS_YOU + "\n").getBytes();
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
				System.err.println("Error: message sending failed for: " + n);
				lockChatrooms.unlock();
				lockSocks.unlock();
				return;
			}
		}
		lockChatrooms.unlock();
		lockSocks.unlock();
	}	

	/**
	* prints the list of available rooms and the number of people currently in it
	* if no rooms are open, it suggests the user to create one
	*/
	private String printRooms(Socket sock) {
		lockChatrooms.lock();
		if (chatrooms.size() == 0) {
			String noRooms = ARROW + "There are no chatrooms open right now! \n";
			noRooms += ARROW + "You can create one by using the \'/createRoom <Room Name>\' command. \n";
			lockChatrooms.unlock();
			return noRooms;
		}

		String rooms = ARROW + "Active rooms are: \n";
		for (String chatroomName : chatrooms.keySet()) {
			HashSet<String> members = chatrooms.get(chatroomName);
			rooms += ARROW + "* " + chatroomName + " (" + members.size() + ") \n";
		}
		lockChatrooms.unlock();
		rooms += ARROW + "End of list. \n";
		return rooms;
	}	

	/**
	* prints all online users 
	**/
	private String printUsers(String username, Socket sock) {
		String users = 	ARROW + "The following users are online: \n";

		lockSocks.lock();
		for (String user : socks.keySet()) {
			users += ARROW + "* " + user;
			if (user.equals(username)) {
				users += " " + THIS_IS_YOU;
			}
			users += "\n";
		}
		lockSocks.unlock();

		users += ARROW + "End of list. \n";
		return users;
	}	

	/**
	* prompts user for the username of whoever they want to PM
	* then asks them for a message they want to send and then sends it
	**/
	private String privateMessage(String[] cmd, String username, OutputStream out) {
		if (cmd.length < 2) {					
			String incorrectArgs = ARROW + "Please specify a user you want to private message after \'/PM\'. \n";
			return incorrectArgs;
		}

		String user = getRestOfCommand(cmd);
		if (!socks.containsKey(user)) {
			String notFound = ARROW + "User not found: " + user + " \n";
			return notFound;
		}

		sendPrivateMessage(username, user);
		return "";
	}

	/**
	* sends a PM to the last person user sent/recieved a PM to
	**/
	private String replyPM(String[] cmd, String username, OutputStream out) {
		lockReplies.lock();
		if (!replyTo.containsKey(username)) {
			String noReply = ARROW + "You haven't been private messaging anyone! \n";
			noReply += ARROW + "This command PMs the last person you PM or the last perso that PM'd you. \n";
			lockReplies.unlock();
			return noReply;					
		}
		lockReplies.unlock();
		if (cmd.length < 2) {					
			String incorrectArgs = ARROW + "Please specify a message you want to pass on to " + replyTo + " \n";
			return incorrectArgs;
		}
		lockReplies.lock();
		String sendTo = replyTo.get(username);
		lockReplies.unlock();
		String pm = getRestOfCommand(cmd) + "\n";
		sendPrivateMessage(username, sendTo, pm);
		return "";
	}

	/**
	* removes specified user completely from the replyTo hashmap
	*/
	private void removeFromReplies(String username) {
		lockReplies.lock();
		Socket sock = new Socket();
		String keyFound = "";
		String inform = "";
		if (replyTo.containsKey(username)) {
			replyTo.remove(username);
		}
		if (replyTo.containsValue(username)) {
			for (String key: replyTo.keySet()) {
				if (replyTo.get(key).equals(username)) {
					// let 'key' know that username logged off and that they won't be able to use /replyPM
					inform = "***" + username + " has logged off. \n" + ARROW + "You now need to send/recieve a PM to use /replyPM. \n" + ARROW;
					sock = socks.get(key);
					keyFound = key;
					replyTo.remove(key);
					break;
				}
			}
		}
		lockReplies.unlock();

		if (!keyFound.equals("")) {
			try {
				sock.getOutputStream().write(inform.getBytes());
			} catch (IOException e) {
				System.err.println("Informing " + keyFound + " that " + username + " logged off failed");
			}			
		}
	}

	/**
	* Sends a private from 'user1' to 'user2'
	**/
	private void sendPrivateMessage(String user1, String user2) {
		byte[] data = new byte[2000];
		int len = 0;
		
		lockSocks.lock();
		Socket sock1 = socks.get(user1);
		Socket sock2 = socks.get(user2);
		lockSocks.unlock();

		String request = ARROW + "Please enter the message you want to send: \n";
		request += ARROW;
		try {
			sock1.getOutputStream().write(request.getBytes());
			if ((len = sock1.getInputStream().read(data)) != -1) {
				String message = new String(data, 0, len);
				sendPrivateMessage(user1, user2, message);
			} else { // user1 left
				removeFromSocks(user1);
				removeFromReplies(user1);
			}			
		} catch (IOException e) {
			System.err.println("Error getting " + user1 + "'s private message");
		}
	}

	/**
	* Sends a private from 'user1' to 'user2' with message 'message'
	**/
	private void sendPrivateMessage(String user1, String user2, String message) {
		lockReplies.lock();
		replyTo.put(user1, user2);
		replyTo.put(user2, user1);
		lockReplies.unlock();		

		lockSocks.lock();
		Socket sock1 = socks.get(user1);
		Socket sock2 = socks.get(user2);
		lockSocks.unlock();		

		String sentMsg = "***PM from " + user1 + ": " + message + ARROW;
		String confirmMsg = ARROW + "***PM sent to " + user2 + ": " + message;
		try {
			sock1.getOutputStream().write(confirmMsg.getBytes());
			sock2.getOutputStream().write(sentMsg.getBytes());
		} catch (IOException e) {
			System.err.println("Private message sending failed from " + user1 + " to " + user2);
		}
	}

	/**
	* removes user from all lists before disconnecting
	*/	
	private void quit(String username, OutputStream out) {
		String bye = ARROW + "Bye!\n";
		try {
			out.write(bye.getBytes());
		} catch (IOException e) {
			System.err.println("Problem saying bye to user " + username);
		}

		removeFromReplies(username);
		removeFromSocks(username);
	}	

	/**
	* removes specified user from the socks hashmap and then closes their socket
	*/
	private void removeFromSocks(String username) {
		lockSocks.lock();
		Socket sock = socks.get(username);
		socks.remove(username);
		lockSocks.unlock();
		try {
			sock.close();
		} catch (IOException e) {
			System.err.println("Error closing " + username + "'s socket");
		}
		return;		
	}

	/**
	* handles looping message sending for each client
	* starts with asking user for their name
	**/
	public void handle_client(Socket sock) {
		InputStream in = null;
		OutputStream out = null;
		try {
			in = sock.getInputStream();
			out = sock.getOutputStream();
		} catch (IOException e) {
			System.err.println("Error: message sending failed.");
			return;
		}

		try {
			String username = getUsername(in, out, sock);
			// lets the user join or create chatrooms
			// or see a menu with command options or quit
			commands(username, in, out, sock);		
		} catch (IOException e) {
			System.err.println("Error getting new user's name");
		}
	}

	public static void main(String[] arg) { 
		if (arg.length > 1) {
			System.err.println("You only have to pass in the port number.");
			System.exit(-1);
		}

		int port = DEFAULT_PORT; // default port
		if (arg.length == 1) {
			try {
				port = Integer.parseInt(arg[0]);
			}
			catch(NumberFormatException e) {
				System.err.println("Invalid port number.");
				System.exit(-1);
			}
		}

		ChatServer myServer = new ChatServer(port);
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
			handle_client(socket);
		}
	}
}