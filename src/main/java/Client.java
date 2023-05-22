import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.time.Instant;
import java.util.List;

public class Client {
  static Logger logger = LoggerFactory.getLogger(Client.class);

  private static ByteBuf createHeader(int identifier, short sender, short length) {
    ByteBuf byteBuf = Unpooled.buffer(0);
    byteBuf.writeInt(identifier);
    byteBuf.writeShort(sender);
    byteBuf.writeShort(length);
    byteBuf.writeInt((int) Instant.now().getEpochSecond());
    byteBuf.writeInt(Instant.now().getNano());
    return byteBuf;
  }

  public static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] =
          (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
  }

  public static void main(String[] args) throws IOException {

    Config config = ConfigFactory.load();
    String configPath = System.getProperty("config.file");

    if (configPath != null) {
      File f = new File(configPath);
      if (f.exists() && !f.isDirectory()) {
        config = ConfigFactory.parseFile(f).resolve();
        logger.info("Configuration is loaded. [{}]", f);
      } else {
        logger.error("Failed to load configuration. Please check the [-Dconfig.file] option.");
        System.exit(0);
      }
    } else {
      logger.debug("Configuration is loaded. (Development Mode)");
    }

    InetAddress address = InetAddress.getByName(config.getString("host"));
    List<Integer> portList = config.getIntList("portList");
    int interval = config.getInt("interval");
    Thread[] multiThreads = new Thread[portList.size()];
    MulticastSocket[] sockets = new MulticastSocket[portList.size()];
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (MulticastSocket socket : sockets) {
        if (socket != null && !socket.isClosed()) {
          socket.close();
        }
      }
    }));
    for (int i = 0; i < portList.size(); i++) {
      int port = portList.get(i);
      int fnum = i;
      sockets[fnum] = new MulticastSocket(port);
      try {
        sockets[fnum].joinGroup(address);
        logger.info("{}port joined", portList.get(fnum));
      } catch (Exception e) {
        logger.error("An error occurred while joining the multicast group.address: {} Port: {}",address, port, e);
        e.printStackTrace();
      }

      String filename = config.getString("ecsroot") + (port - 50000) + "_ecs.csv";
      File file = new File(filename);
      BufferedReader readfile = new BufferedReader(new FileReader(file));
      multiThreads[i] =
          new Thread(
              () -> {
                String ecsmsg = null;
                while (true) {
                  try {
                    ecsmsg = readfile.readLine();
                    assert ecsmsg != null;
                    String ecsbyte = ecsmsg.split("\\|")[2];
                    ecsbyte = ecsbyte.replace("0x", "");
                    byte[] bytes = hexStringToByteArray(ecsbyte);
                    DatagramPacket msg = new DatagramPacket(bytes, bytes.length, address, port);
                    logger.info(
                        "{}port Send: {} \nMessage_Identifier: {}\nMessage_Length: {}\n",
                        port,
                        DatatypeConverter.printHexBinary(msg.getData()),
                        ecsmsg.split("\\|")[0],
                        msg.getLength());
                    sockets[fnum].send(msg);
                    Thread.sleep(interval);
                  } catch (Exception e) {
                    logger.error("An error occurred in the socket thread. Port: {}", port, e);
                    e.printStackTrace();
                    break;
                  }
                }
              });
      multiThreads[i].start();
    }
  }
}
