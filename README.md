# P2P Secure Chat & File Transfer over UDP

This project implements a peer-to-peer (P2P) chat application in Java, primarily interacted with via a Swing-based Graphical User Interface (GUI), where nodes connect directly using UDP for communication. It features end-to-end encryption for chats and supports small file transfers between connected peers. A central coordination server facilitates peer discovery and connection setup.

## Features

* **Peer-to-Peer Communication:** Clients connect directly after initial coordination.
* **UDP Based:** Uses UDP for low-latency communication.
* **End-to-End Encryption (E2EE):** Chat messages and file data are encrypted using AES-GCM derived from an ECDH key exchange. Ensures only the communicating peers can read the content.
* **User Interface:** Provides both a user-friendly Graphical User Interface (GUI) for most operations and a Command-Line Interface (CLI).
* **Coordination Server:** Helps nodes find each other and exchange necessary information (IP addresses, public keys) to establish a direct connection.
* **File Transfer:** Supports sending and receiving small files (up to 1 MiB by default) between connected peers with negotiation (accept/reject).
* **Basic UDP Reliability:** Implements a simple Stop-and-Wait acknowledgment mechanism for file transfer chunks.
* **Chat History:** Saves chat logs to local CSV files.
* **Downloads Folder:** Saves received files locally.

## Prerequisites

* **Java Development Kit (JDK):** Version 8 or higher recommended. Make sure `javac` and `java` commands are available in your system's PATH.
* **JSON Library:** A JSON parsing library is required. This project assumes you have `org.json.jar` (available from [MVNRepository](https://mvnrepository.com/artifact/org.json/json) or other sources).
* **(Optional for GUI Icons):** The GUI attempts to load a copy icon from `src/main/resources/icons/copy_icon.png`. If this file is not present, a simple text button `[C]` will be used as a fallback. You can create these directories and add a 16x16 or 20x20 PNG icon if desired.

## Setup

1.  **Clone/Download:** Get the project source code onto your local machine.
2.  **JSON Library:**
    * Create a directory named `lib` in the project's root folder (alongside `src`).
    * Download `json-YYYYMMDD.jar` (or similar) from a source like MVNRepository.
    * Rename the downloaded file to `json.jar` and place it inside the `lib` directory.
3.  **(Optional for GUI Icons):**
    * Create a directory path `src/main/resources/icons/` within your project structure.
    * Place an icon file named `copy_icon.png` (e.g., a 16x16 or 20x20 PNG) in this `icons` directory.

    Your project structure should look something like this:

    ```
    p2p-chat-udp/
    ├── lib/
    │   └── json.jar
    ├── src/
    │   └── main/
    │       ├── java/
    │       │   └── com/
    │       │       └── p2pchat/
    │       │           ├── common/
    │       │           ├── node/
    │       │           └── server/
    │       └── resources/      (Optional, for GUI icons)
    │           └── icons/      (Optional, for GUI icons)
    │               └── copy_icon.png (Optional, for GUI icons)
    ├── chat_history/ (Will be created automatically)
    └── downloads/    (Will be created automatically)
    └── README.md     (This file)
    ```

## Compilation

1.  Open a terminal or command prompt in the project's root directory (`p2p-chat-udp`).
2.  Create a `bin` directory for the compiled `.class` files:
    ```bash
    mkdir bin
    ```
3.  Compile all Java source files, referencing the JSON library and outputting to the `bin` directory:

    * **Linux/macOS:**
        ```bash
        javac -cp "lib/json.jar" -sourcepath src -d bin src/main/java/com/p2pchat/common/*.java src/main/java/com/p2pchat/node/**/*.java src/main/java/com/p2pchat/server/*.java src/main/java/com/p2pchat/server/config/*.java src/main/java/com/p2pchat/server/model/*.java src/main/java/com/p2pchat/server/network/*.java src/main/java/com/p2pchat/server/service/*.java
        ```
        *(Note: Added `src/main/resources` to classpath for the icon. If you are not using the icon, you can omit `:src/main/resources`)*
    * **Windows:**
        ```bash
        javac -cp "lib/json.jar" -sourcepath src -d bin src\main\java\com\p2pchat\common\*.java src\main\java\com\p2pchat\node\**\*.java src\main\java\com\p2pchat\server\*.java src\main\java\com\p2pchat\server\config\*.java src\main\java\com\p2pchat\server\model\*.java src\main\java\com\p2pchat\server\network\*.java src\main\java\com\p2pchat\server\service\*.java

        ```
        *(Note: Added `src/main/resources` to classpath for the icon. If you are not using the icon, you can omit `;src/main/resources`. Windows might use semicolons `;` instead of colons `:` in classpath depending on the shell, but quotes often help)*

    This command compiles all `.java` files within the `common`, `node`, and `server` packages and their subpackages (as per their `package` declarations, which include `main.java.` prefix).

## Running the Application

You need to run the Coordination Server first, and then run two (or more) P2P Node clients.

**1. Running the Coordination Server**

* The server simply listens for clients.
* **Challenges with Local Server and External Nodes:** If you run the `CoordinationServer` on your local machine (e.g., behind a home router/NAT), nodes from *different* networks (i.e., outside your local network) will likely **not** be able to reach it. This is because:
    * Your local machine's IP address is typically a private IP (e.g., `192.168.x.x`), not directly routable from the public internet.
    * Your router/firewall will block incoming connections to arbitrary ports on your local machine by default.
    * To allow external connections, you would need to configure **port forwarding** on your router (e.g., forward UDP port 19999 to your local machine's private IP) and ensure your firewall allows incoming UDP traffic on that port. This can be complex and vary between routers.
* **Using a Hosted Server (Recommended for Testing Across Networks):**
    To simplify testing with nodes on different networks, a temporary instance of the coordination server has been hosted on a Google Cloud VM.
    * **Public IP Address:** `34.136.226.168` (Listens on UDP port `19999`)
    * **Availability:** This is a temporary server for demonstration/testing and will be turned off once free credits expire.
    * Firewall rules on the cloud instance have been configured to allow incoming UDP traffic on port `19999`.
    * You do **not** need to run the `CoordinationServer` yourself if you use this hosted IP.
* **Running Your Own Server (For Local Testing or If Hosted is Down):**
    Run it in a dedicated terminal window. From the project root directory:
    * **Linux/macOS:**
        ```bash
        java -cp bin:lib/json.jar main.java.com.p2pchat.server.CoordinationServer
        ```
    * **Windows:**
        ```bash
        java -cp "bin;lib/json.jar" main.java.com.p2pchat.server.CoordinationServer
        ```
    * You should see output indicating the server is listening, e.g., `[*] Coordination Server listening on UDP port 19999`.
    * Keep this terminal window open while clients are running. Use Ctrl+C to stop the server.

**2. Running the P2P Node Client**

The application will primarily launch a Graphical User Interface (GUI).

* Each client needs its own terminal window to launch.
* You need to provide the IP address of the machine running the Coordination Server as a command-line argument.
* From the project root directory (ensure your classpath includes `src/main/resources` if you compiled with it for the icon):

    * **To use the hosted Google Cloud server:**
        * Linux/macOS: `java -cp bin:lib/json.jar:src/main/resources main.java.com.p2pchat.node.P2PNode 34.136.226.168`
        * Windows: `java -cp "bin;lib/json.jar;src/main/resources" main.java.com.p2pchat.node.P2PNode 34.136.226.168`
    * **If server is running on the *same* local machine (and you are running your own server):**
        * Linux/macOS: `java -cp bin:lib/json.jar:src/main/resources main.java.com.p2pchat.node.P2PNode 127.0.0.1`
        * Windows: `java -cp "bin;lib/json.jar;src/main/resources" main.java.com.p2pchat.node.P2PNode 127.0.0.1`
    * **If server is running on a *different local* machine (e.g., IP `A.B.C.D`, and you are running your own server):**
        * Linux/macOS: `java -cp bin:lib/json.jar:src/main/resources main.java.com.p2pchat.node.P2PNode A.B.C.D`
        * Windows: `java -cp "bin;lib/json.jar;src/main/resources" main.java.com.p2pchat.node.P2PNode A.B.C.D`
    * **If you omit the IP address, it defaults to `127.0.0.1`.**
    * *(Note: If you didn't compile with `src/main/resources` in the classpath, you can omit it from these run commands as well.)*

* **Username Prompt:** Upon launching, a dialog box will appear asking you to "Enter your desired username". Type your username and click "OK". This username will be visible to your chat peers.
* The GUI window will then appear. After successful registration with the server, you will see your assigned **Node ID** in the GUI.

## Using the Client (Graphical User Interface - GUI)

The application will primarily launch a Graphical User Interface (GUI) built with Java Swing.

**1. Understanding the GUI Layout**

The main window titled "P2P Secure Chat" is divided into several sections:

* **Top Panel:**
    * **Node ID Display:** Shows "Node ID: \<Your Node ID\>" once registered with the server.
        * **Copy ID Button (Icon/\[C]):** Next to the Node ID, click this small icon (or `[C]` button) to copy your Node ID to the clipboard. This is useful for sharing with peers.
    * **Status Label:** Displays the current status of the application (e.g., "Status: Initializing...", "Status: Disconnected", "Status: ✅ Connected to \<PeerName\>"). The color of the text also changes to indicate the state (e.g., orange for connecting, green for connected, red for disconnected).
    * **Action Buttons:**
        * `Connect`: Initiates a connection to another peer.
        * `Disconnect`: Disconnects from the currently connected peer or cancels an ongoing connection attempt.
        * `Send File`: Allows you to select and send a file to the connected peer.
        * `History`: Lets you view your chat history.

* **Center Panel (Chat Area):**
    * This large text area displays the chat messages between you and your peer. Messages are prefixed with the sender's name (e.g., `[You]: Hello`, `[PeerName]: Hi there!`).

* **Right Panel (File Transfers):**
    * This panel, labeled "File Transfers," shows the progress of any ongoing or completed file transfers. Each transfer will display:
        * Direction (Sending/Receiving) and Filename.
        * Status (e.g., OFFER_SENT, AWAITING_ACCEPT, TRANSFERRING_RECV, COMPLETED, FAILED).
        * A progress bar showing the percentage of completion.
    * Completed received files can be clicked to attempt opening them with the system's default application.

* **Bottom Panel (Message Input):**
    * **Message Field:** A text field where you type your chat messages.
    * **Send Button:** Click this button to send the message typed in the message field. Pressing `Enter` in the message field also sends the message.

**2. GUI Workflow and Actions**

* **Initial State:**
    * When the GUI starts, the status will be "Status: Initializing...".
    * The application will then attempt to register with the server. The status will change to "Status: Registering...".
    * Once successfully registered, your Node ID will appear, the `Copy ID` button will become active, and the status will change to "Status: Disconnected". The `Connect` and `History` buttons will also become enabled.

* **Connecting to a Peer:**
    1.  Ensure both you and your peer have launched the client and are registered (Status: Disconnected, and Node ID is visible).
    2.  Share your Node IDs with each other (e.g., using the `Copy ID` button).
    3.  Click the `Connect` button. A dialog box will appear asking for "Peer Node ID".
    4.  Enter your peer's Node ID into the dialog and click "OK".
    5.  Your peer must also perform the same steps, entering *your* Node ID.
    6.  The `Status Label` will update to "Status: Waiting for \<PeerID_Prefix\> match...", then "Status: Attempting P2P with \<PeerID_Prefix\>...".
    7.  Once the connection is successfully established and keys are exchanged, the status will change to "Status: ✅ Connected to \<PeerName\>" (text in green). The `Disconnect` and `Send File` buttons, as well as the message input field and `Send` button, will become enabled.

* **Chatting:**
    1.  Once connected, type your message into the **Message Field** at the bottom.
    2.  Click the `Send` button or press `Enter`.
    3.  Your message will appear in the **Chat Area** prefixed with `[You]:`.
    4.  Incoming messages from your peer will appear prefixed with `[PeerName]:`.

* **Sending a File:**
    1.  Ensure you are connected to a peer.
    2.  Click the `Send File` button.
    3.  A file chooser dialog will open. Select the file you wish to send and click "Open" (or equivalent).
    4.  An entry for this file transfer will appear in the **File Transfers** panel, initially with a status like "Sending 'filename' (OFFER_SENT)" or "(AWAITING_ACCEPT)".
    5.  Your peer will receive a dialog box notifying them of the incoming file offer.

* **Receiving a File:**
    1.  When a peer offers you a file, a dialog box will pop up showing the filename, size, and sender's username, asking "Accept this file?".
    2.  Click `Yes` to accept or `No` to reject.
    3.  If you accept, an entry for the file will appear in the **File Transfers** panel with status "Receiving 'filename' (TRANSFERRING_RECV)". The progress bar will update as the file is downloaded.
    4.  Once completed, the status will change to "COMPLETED". The file will be saved in the `downloads` directory in your project folder.
    5.  You can click on the completed entry in the **File Transfers** panel to try and open the received file.
    6.  If you reject, the sender will be notified.

* **Viewing Chat History:**
    1.  Click the `History` button.
    2.  **If Connected:** A dialog will open displaying the chat history with the currently connected peer.
    3.  **If Disconnected:** A dialog will appear listing all available past chat histories (identified by peer username and part of their Node ID). Select a history and click "OK" (or it might open directly, depending on implementation details) to view it.
    4.  The history dialog has a "Close" button. If you accessed it from the list of past chats, it may also have a "Back to List" button.

* **Disconnecting:**
    1.  If connected, click the `Disconnect` button. The status will change to "Status: Disconnected".
    2.  If you are in the process of connecting (e.g., "Waiting for match" or "Attempting P2P"), clicking `Disconnect` will cancel the attempt.

* **Quitting the Application:**
    * Close the main GUI window (click the 'X' button). This will initiate a clean shutdown of the P2P node.

**3. Example GUI Workflow (using the hosted server `34.136.226.168`)**

1.  **(No need to start local server if using the hosted one)**
2.  **Start Client 1 (Alice):** Run `java -cp "bin;lib/json.jar;src/main/resources" main.java.com.p2pchat.node.P2PNode 34.136.226.168` (adjust for OS). Enter "Alice" at the username prompt. The GUI appears. Alice clicks the `Copy ID` button.
3.  **Start Client 2 (Bob):** Run `java -cp "bin;lib/json.jar;src/main/resources" main.java.com.p2pchat.node.P2PNode 34.136.226.168` (adjust for OS). Enter "Bob" at the username prompt. The GUI appears. Bob clicks `Copy ID`.
4.  Alice and Bob share their Node IDs.
5.  **Connect (Alice):** Alice clicks `Connect`, enters Bob's Node ID, and clicks "OK".
6.  **Connect (Bob):** Bob clicks `Connect`, enters Alice's Node ID, and clicks "OK".
7.  **Connection Establishes:** Both GUIs update their status to "✅ Connected to [PeerName]".
8.  **Chat (Alice):** Alice types "Hello Bob via GUI!" in the message field and clicks `Send`.
9.  **Chat (Bob):** Bob sees "[Alice]: Hello Bob via GUI!" in his chat area.
10. **Send File (Alice):** Alice clicks `Send File`, selects a file (e.g., `test.txt`), and clicks "Open".
11. **Receive Offer (Bob):** Bob gets a dialog: "Incoming file offer from Alice...". Bob clicks `Yes`.
12. **Completion:** Both see "COMPLETED" in their File Transfers panel. Bob finds `test.txt` in his `downloads` folder.
13. **Quit:** Both close their GUI windows.

## Using the Client (CLI Commands)

While the GUI is the primary interface, the CLI is available if the GUI fails or for advanced users. If the client is launched and the GUI fails to start, or if it's built/run to bypass the GUI, you might get a command prompt.

Once the client is running and registered (you will see a dynamic prompt like `[?] Enter 'connect', 'status', 'id', 'quit': ` in the console when disconnected), you can use the following commands:

* **`id`**: Displays your username and unique Node ID.
* **`status`** or **`s`**: Shows your current connection status and lists ongoing file transfers.
* **`connect <peer_node_id>`**: Initiates a connection attempt to the specified peer.
    * Example: `connect e361fe63-254a-4ece-b555-425f8571ea2c`
* **`chat <message>`** or **`c <message>`**: Sends an encrypted chat message.
    * Example: `chat Hello there!`
* **(Default Input)**: When connected, simply typing text and pressing Enter (without a command) sends it as a chat message.
* **`send <filepath>`**: Initiates sending a file.
    * Example: `send my_document.txt`
* **`accept <transfer_id>`**: Accepts an incoming file offer.
* **`reject <transfer_id>`**: Rejects an incoming file offer.
* **`disconnect`** or **`cancel`**: Disconnects or cancels a connection attempt.
* **`quit`** or **`exit`**: Shuts down the client.

*(The GUI workflow detailed earlier is the primary way to interact with this application. The CLI commands listed above are available for advanced use or if the GUI cannot be used.)*

## Directories

* **`lib/`**: Contains the required `json.jar` library.
* **`src/`**: Contains all the Java source code.
    * **`src/main/resources/`**: (Optional) Can hold resources like GUI icons.
* **`bin/`**: Contains the compiled `.class` files (created during compilation).
* **`chat_history/`**: Automatically created by clients. Stores `.csv` files containing chat logs for each peer connection. Files are named like `chat_<PeerUsername>_<PeerNodeID>.csv`.
* **`downloads/`**: Automatically created by clients. Stores files received via the file transfer feature.

## Technical Details

* **Networking:** Uses Java's `DatagramSocket` and `DatagramPacket` for UDP communication. Basic UDP hole punching (sending pings) is used to attempt direct P2P connection through NATs.
* **Encryption:**
    * Key Exchange: Elliptic Curve Diffie-Hellman (ECDH) using Java Cryptography Architecture (JCA/JCE).
    * Session Encryption: AES-GCM (256-bit) for authenticated encryption of chat messages and file chunks. Provides confidentiality and integrity.
* **Coordination:** The server acts solely as an introducer and does not relay any chat or file data.

## Limitations

* **UDP Unreliability:** UDP does not guarantee packet delivery or order for chat messages. Messages *might* be lost. File transfers use a simple acknowledgment mechanism, making them slow but more reliable than chat.
* **NAT Traversal:** Basic hole punching may not work through all types of NATs (especially symmetric NATs). If direct P2P connection fails, communication will not be possible.
* **File Transfer Speed:** The Stop-and-Wait ACK mechanism for file transfers is simple but very slow compared to TCP or more advanced UDP reliability protocols. The file size limit (default 1 MiB) reflects this.
* **Server Single Point of Failure:** If the coordination server is down, new connections cannot be initiated.
* **Security:** Relies on trusting the coordination server during the initial public key exchange. No mechanism to verify peer identity beyond what the server provides.
