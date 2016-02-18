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

// TODO: make the strings and ioexception catching more consistent !!
public class ChatServer {

	private static final String THIS_IS_YOU = "(** this is you!)";
	private static final String ARROW = ">> ";
	
	private Lock lockSocks; // for the list of sockets
	private Lock lockChatrooms; // for the list of people in each chatroom
	private Lock lockReplies; // for the list of users to reply to
	private HashMap<String,Socket> socks; // list of sockets for the chatroom
	private HashMap<String, HashSet<String>> chatrooms; // chatroom and chatroom members
	private HashMap<String, String> replyTo; // keeps track of who to reply to for each user
	private ServerSocket server_sock;

	/**
	* binds socket to port
	**/
	public ChatServer(int port) throws IOException {
		lockSocks = new ReentrantLock();
		lockChatrooms = new ReentrantLock();
		lockReplies = new ReentrantLock();
		socks = new HashMap<String,Socket>();
		chatrooms = new HashMap<String, HashSet<String>>(); 
		replyTo = new HashMap<String, String>();

		// auto create one chatroom so that the first user doesn't have to
		chatrooms.put("main", new HashSet<String>());

		binding(port);
		createThreads();
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

		// TODO: add a command /users that shows what users are online (for PM purposes)
		String listOfCommands = ARROW + "Here are a list of commands you can do! \n";
		listOfCommands += ARROW + "* /join <Room Name>: lets you join the room called \'Room Name\' \n";
		listOfCommands += ARROW + "* /rooms: prints out the list of rooms and how many people are in each \n";
		listOfCommands += ARROW + "* /createRoom <Room Name>: creates a chatroom called \'Room Name\' \n";
		listOfCommands += ARROW + "* /deleteRoom <Room Name>: deletes the chatroom called \'Room Name\' \n";
		listOfCommands += ARROW + "* /changeUsername <Username>: changes your username to \'Username\' \n";
		listOfCommands += ARROW + "The following commands can also be run within chatrooms: \n";
		listOfCommands += ARROW + "* /users: prints out the list of users are online \n";				
		listOfCommands += ARROW + "* /PM <Username>: to privately message user, \'Username\' \n";
		listOfCommands += ARROW + "* /replyPM <Message>: private message to last person who sent PM with message \'Message\' \n";
		listOfCommands += ARROW + "* /help <Room Name>: lists these command options \n";
		listOfCommands += ARROW + "* /quit: to exit the chat server \n";
		listOfCommands += ARROW + "End of list. \n";

		out.write(listOfCommands.getBytes());
		out.write(ARROW.getBytes());

		while ((len = in.read(data)) != -1) {
			String message = new String(data, 0, len);

			String[] cmd = message.substring(0, message.length()-2).split(" ");
			if (cmd.length == 0) { // user didnt input anything; just continue
				continue;
			}
			if (cmd[0].equals("/join")) {
				if (cmd.length < 2) {
					String incorrectArgs = ARROW + "Please specify a chatroom name after \'/join\'. \n";
					incorrectArgs += ARROW;
					out.write(incorrectArgs.getBytes());
					continue;
				}

				String groupName = getRestOfCommand(cmd);
				if (!chatrooms.containsKey(groupName)) {
					String noGroup = ARROW + "That is not an available chatroom name. \n";
					noGroup += ARROW + "Please try \'/rooms\' for a list of available rooms. \n";
					noGroup += ARROW;
					out.write(noGroup.getBytes());
					continue;
				}

				newUserToGroup(username, groupName, sock);
				chat(groupName, username, in, out, sock);
			} else if (cmd[0].equals("/rooms")) {
				printRooms(sock);
			} else if (cmd[0].equals("/createRoom")) {
				if (cmd.length < 2) {
					String incorrectArgs = ARROW + "Please specify a chatroom name after \'/createRoom\'. \n";
					incorrectArgs += ARROW;
					out.write(incorrectArgs.getBytes());
					continue;
				}

				String groupName = getRestOfCommand(cmd);
				lockChatrooms.lock();
				chatrooms.put(groupName, new HashSet<String>());
				lockChatrooms.unlock();

				String created = ARROW + groupName + " created. \n";
				out.write(created.getBytes());
			} else if (cmd[0].equals("/deleteRoom")) {
				if (cmd.length < 2) {
					String incorrectArgs = ARROW + "Please specify a chatroom name after \'/deleteRoom\'. \n";
					incorrectArgs += ARROW;
					out.write(incorrectArgs.getBytes());
					continue;
				}

				String groupName = getRestOfCommand(cmd);
				lockChatrooms.lock();
				if (!chatrooms.containsKey(groupName)) {
					String noRoom = ARROW + "There is no room called " + groupName + " found. \n";
					noRoom += ARROW;
					out.write(noRoom.getBytes());
					lockChatrooms.unlock();
					continue;
				}

				HashSet<String> members = chatrooms.get(groupName);
				if (members.size() != 0) {
					String noDelete = ARROW + "You can't delete a room with people still in it! \n";
					noDelete += ARROW;
					out.write(noDelete.getBytes());
					lockChatrooms.unlock();
					continue;
				}

				chatrooms.remove(groupName);
				lockChatrooms.unlock();

				String deleted = ARROW + groupName + " deleted. \n";
				out.write(deleted.getBytes());
			} else if (cmd[0].equals("/changeUsername")) {
				if (cmd.length < 2) {
					String incorrectArgs = ARROW + "Please specify a username you want to change to after \'/deleteRoom\'. \n";
					incorrectArgs += ARROW;
					out.write(incorrectArgs.getBytes());
					continue;
				} 

				String desiredName = getRestOfCommand(cmd);
				if (socks.containsKey(desiredName)) {
					String takenName = ARROW + "That name has already been taken! Please choose another. \n";
					takenName += ARROW;
					out.write(takenName.getBytes());
					continue;
				}

				changeUsername(username, desiredName);

				username = desiredName;
				String changeSucess = ARROW + "Name has been changed to: " + username + "\n";
				out.write(changeSucess.getBytes());
			} else if (cmd[0].equals("/users")) {
				printUsers(username, sock);
			} else if (cmd[0].equals("/PM")) {
				privateMessage(cmd, username, sock);
			} else if (cmd[0].equals("/replyPM")) {
				replyPM(cmd, username, sock);
			} else if (cmd[0].equals("/help")) {
				out.write(listOfCommands.getBytes());
			} else if (cmd[0].equals("/quit")) {
				quit(username);
			} else { // error - let the user know the list of commands!
				String error = ARROW + "Whoops! That wasn't a valid command.. try typing \'/help\' for a list of commands! \n";
				out.write(error.getBytes());
			}
			out.write(ARROW.getBytes());
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
		StringBuffer rtn = new StringBuffer();
		for (int i = 1; i < command.length; i++) {
			rtn.append(command[i]);
			if (i != command.length - 1) {
				rtn.append(" ");
			}
		}
		return rtn.toString();
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
				String tryAgain = ARROW + "That user name has been taken!\n";
				tryAgain += ARROW + "Login Name? \n";
				tryAgain += ARROW;
				out.write(tryAgain.getBytes());
			}
			lockSocks.unlock();

			if (!loop) { // valid username
				String customWelcome = ARROW + "Welcome " + username + "!\n";
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
	private void newUserToGroup(String username, String groupName, Socket newSock) throws IOException {
		String welcome = "Welcome to " + groupName + "!\n";
		newSock.getOutputStream().write(welcome.getBytes());

		lockChatrooms.lock();
		HashSet<String> members = chatrooms.get(groupName);
		members.add(username);
		lockChatrooms.unlock();

		// tell everyone in the chatroom that new user has entered
		StringBuffer message = new StringBuffer();
		message.append("Entering room: ");
		message.append(username);
		message.append("\n");
		byte[] m = message.toString().getBytes();
		sendMessage(groupName, message.toString(), username);

		StringBuffer users = new StringBuffer();
		users.append(ARROW + "Current users online: \n");

		Socket s;
		OutputStream out;
		lockChatrooms.lock();
		lockSocks.lock();
		Iterator<String> it = members.iterator();
		while (it.hasNext()) {
			String n = it.next();
			s = socks.get(n);
			users.append(ARROW + "* ");
			users.append(n);
			users.append(" ");
			if (s == newSock) {
				users.append(THIS_IS_YOU);
			}
			users.append("\n");
		}
		lockSocks.unlock();
		lockChatrooms.unlock();
		users.append(ARROW + "End of list. \n");
		users.append(ARROW);

		try {
			newSock.getOutputStream().write(users.toString().getBytes());
		} catch (IOException e) {
			System.err.println("Error: message sending failed for: " + username);
		}
	}

	/**
	* allows the user to chat in the specified chat room
	**/ 
	private void chat(String groupName, String username, InputStream in, OutputStream out, Socket sock) throws IOException {
		String help = "You can use the following commands in the chatroom: \n";
		help += ARROW + "* /leave: to leave the chatroom \n";
		help += ARROW + "* /users: prints out the list of users are online \n";				
		help += ARROW + "* /PM <Username>: to privately message user, \'Username\' \n";
		help += ARROW + "* /replyPM <Message>: private message to last person who sent PM with message \'Message\' \n";
		help += ARROW + "* /help <Room Name>: lists these command options \n";
		help += ARROW + "* /quit: to exit the chat server \n";
		help += ARROW + "End of list. \n";
		out.write(help.getBytes());
		out.write(ARROW.getBytes());
		
		byte[] data = new byte[2000];
		int len = 0;

		while ((len = in.read(data)) != -1) {
			String message = new String(data, 0, len);

			// TODO: allow any user in this group to /PM anyone else online (not limited to this group)
			String[] cmd = message.substring(0, message.length()-2).split(" ");
			if (cmd[0].equals("/leave")) { // user to leave the chatroom - remove from chatroom list
				String leftRoom = "* user has left chat: " + username;
				sendMessageToChatroom(groupName, leftRoom, username);
				lockChatrooms.lock();
				HashSet members = chatrooms.get(groupName);
				members.remove(username);
				lockChatrooms.unlock();
				return;
			} else if (cmd[0].equals("/PM")) {
				privateMessage(cmd, username, sock);
			} else if (cmd[0].equals("/replyPM")) {
				replyPM(cmd, username, sock);
			} else if (cmd[0].equals("/users")) {
				printUsers(username, sock);
			} else if (cmd[0].equals("/quit")) {
				quit(username);
			} else if (cmd[0].equals("/help")) {
				out.write(help.getBytes());
			} else { // a normal message to the members of the chatroom
				sendMessage(groupName, username + ": " + message, username);
			}
			out.write(ARROW.getBytes());
		}
		if (len == -1) { // if user leaves server remove them from all lists and close the socket
			String leftRoom = ARROW + "* user has left chat: " + username;
			sendMessageToChatroom(groupName, leftRoom, username);
			lockChatrooms.lock();
			HashSet members = chatrooms.get(groupName);
			members.remove(username);
			lockChatrooms.unlock();
			
			removeFromSocks(username);
			return;
		}
	}

	/**
	* sends a message to everyone but the sender's room for now
	**/
	private void sendMessage(String groupName, String message, String username) {
		byte[] m = (message + ARROW).getBytes();
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
						lockChatrooms.unlock();
						lockSocks.unlock();
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
				System.err.println("Error: message sending failed for: " + n );
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
	private void printRooms(Socket sock) throws IOException {
		lockChatrooms.lock();
		if (chatrooms.size() == 0) {
			String noRooms = ARROW + "There are no chatrooms open right now! \n";
			noRooms += ARROW + "You can create one by using the \'/createRoom <Room Name>\' command. \n";
			sock.getOutputStream().write(noRooms.getBytes());
			lockChatrooms.unlock();
			return;
		}

		String rooms = ARROW + "Active rooms are: \n";
		for (String chatroomName : chatrooms.keySet()) {
			HashSet<String> members = chatrooms.get(chatroomName);
			rooms += ARROW + "* " + chatroomName + " (" + members.size() + ") \n";
		}
		lockChatrooms.unlock();
		sock.getOutputStream().write(rooms.getBytes());
	}	

	/**
	* prints all online users 
	**/
	private void printUsers(String username, Socket sock) throws IOException {
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
		sock.getOutputStream().write(users.getBytes());
	}	

	/**
	* prompts user for the username of whoever they want to PM
	* then asks them for a message they want to send and then sends it
	**/
	private void privateMessage(String[] cmd, String username, Socket sock) throws IOException {
		OutputStream out = sock.getOutputStream();
		if (cmd.length < 2) {					
			String incorrectArgs = ARROW + "Please specify a user you want to private message after \'/PM\'. \n";
			out.write(incorrectArgs.getBytes());
			return;
		}

		String user = getRestOfCommand(cmd);
		if (!socks.containsKey(user)) {
			String notFound = ARROW + "User not found: " + user + " \n";
			out.write(notFound.getBytes());
			return;
		}

		sendPrivateMessage(username, user);
	}

	/**
	* sends a PM to the last person user sent/recieved a PM to
	**/
	private void replyPM(String[] cmd, String username, Socket sock) throws IOException {
		OutputStream out = sock.getOutputStream();
		lockReplies.lock();
		if (!replyTo.containsKey(username)) {
			String noReply = ARROW + "You haven't been private messaging anyone! \n";
			noReply += ARROW + "This command PMs the last person you PM or the last perso that PM'd you. \n";
			out.write(noReply.getBytes());
			lockReplies.unlock();
			return;					
		}
		lockReplies.unlock();
		if (cmd.length < 2) {					
			String incorrectArgs = ARROW + "Please specify a message you want to pass on to " + replyTo + " \n";
			out.write(incorrectArgs.getBytes());
			return;
		}
		lockReplies.lock();
		String sendTo = replyTo.get(username);
		lockReplies.unlock();
		String pm = getRestOfCommand(cmd) + "\n";
		sendPrivateMessage(username, sendTo, pm);
	}

	/**
	* removes specified user completely from the replyTo hashmap
	*/
	private void removeFromReplies(String username) throws IOException {
		lockReplies.lock();
		if (replyTo.containsKey(username)) {
			replyTo.remove(username);
		}
		if (replyTo.containsValue(username)) {
			for (String key: replyTo.keySet()) {
				if (replyTo.get(key).equals(username)) {
					// let 'key' know that username logged off and that they won't be able to use /replyPM
					String inform = "***" + username + " has logged off.  You now need to send/recieve a PM to use /replyPM. \n" + ARROW;
					Socket informSock = socks.get(key);
					informSock.getOutputStream().write(inform.getBytes());

					replyTo.remove(key);
					break;
				}
			}
		}
		lockReplies.unlock();
	}

	/**
	* Sends a private from 'user1' to 'user2'
	**/
	private void sendPrivateMessage(String user1, String user2) throws IOException {
		byte[] data = new byte[2000];
		int len = 0;
		
		lockSocks.lock();
		Socket sock1 = socks.get(user1);
		Socket sock2 = socks.get(user2);
		lockSocks.unlock();

		String request = ARROW + "Please enter the message you want to send: \n";
		request += ARROW;
		sock1.getOutputStream().write(request.getBytes());

		if ((len = sock1.getInputStream().read(data)) != -1) {
			String message = new String(data, 0, len);
			sendPrivateMessage(user1, user2, message);
		} else { // user1 left
			removeFromSocks(user1);
			removeFromReplies(user1);
		}
	}

	/**
	* Sends a private from 'user1' to 'user2' with message 'message'
	**/
	private void sendPrivateMessage(String user1, String user2, String message) throws IOException {
		lockReplies.lock();
		replyTo.put(user1, user2);
		replyTo.put(user2, user1);
		lockReplies.unlock();		

		lockSocks.lock();
		Socket sock2 = socks.get(user2);
		lockSocks.unlock();		

		message = "***PM from " + user1 + ": " + message + ARROW;
		sock2.getOutputStream().write(message.getBytes());
	}

	/**
	* removes user from all lists before disconnecting
	*/	
	private void quit(String username) throws IOException {
		OutputStream out = socks.get(username).getOutputStream();

		String bye = ARROW + "Bye!\n";
		out.write(bye.getBytes());

		removeFromReplies(username);
		removeFromSocks(username);
	}	

	/**
	* removes specified user from the socks hashmap and then closes their socket
	*/
	private void removeFromSocks(String username) throws IOException {
		lockSocks.lock();
		Socket sock = socks.get(username);
		socks.remove(username);
		lockSocks.unlock();
		sock.close();
		return;		
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

	public static void main(String[] arg) throws IOException { 
		if (arg.length > 1) {
			System.err.println("You only have to pass in the port number.");
			System.exit(-1);
		}

		int port = 5555; // default port
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
			try {
				handle_client(socket);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}