import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * connect-metrics.yaml 시험용 빈 JVM (S15P31A108-261). CI cdc-stack 잡이 서버와 같은 JMX exporter javaagent 를 붙여 돌린다.
 *
 * Connect·Debezium 이 등록하는 것과 같은 이름·속성의 MBean 을 올려 두고 기다린다. 잡은 에이전트 HTTP 에서 알림이 읽는
 * 시계열(커넥터·task 상태, Debezium 연결)이 나오는지 본다. 설정이 틀리면 에이전트가 JVM 을 exit 1 로 끝낸다(서버에서는
 * Connect 가 뜨지 않음). 이름은 11-06 로컬 Connect(debezium/connect 2.7.3)에서 본 그대로다.
 *
 * 실행: java -javaagent:<jar>=127.0.0.1:19404:connect-metrics.yaml tests/ConnectMetricsCheck.java <대기 ms>
 */
public class ConnectMetricsCheck {

    public static void main(String[] args) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        String connector = "beez-published-analysis-1";
        register(server, "kafka.connect:type=connector-metrics,connector=" + connector,
                Map.of("status", "running", "connector-type", "source"));
        register(server, "kafka.connect:type=connector-task-metrics,connector=" + connector + ",task=0",
                Map.of("status", "running"));
        register(server, "kafka.connect:type=connect-worker-metrics,connector=" + connector,
                Map.of("connector-running-task-count", 1.0, "connector-failed-task-count", 0.0));
        register(server, "kafka.connect:type=connect-worker-metrics",
                Map.of("connector-count", 1.0, "task-count", 1.0));
        register(server, "debezium.postgres:type=connector-metrics,context=streaming,server=beez",
                Map.of("Connected", true, "TotalNumberOfEventsSeen", 0L));
        Thread.sleep(Long.parseLong(args[0]));
    }

    private static void register(MBeanServer server, String name, Map<String, Object> attributes) throws Exception {
        server.registerMBean(new Fixed(attributes), new ObjectName(name));
    }

    /** 고정 값만 돌려주는 MBean. 속성 타입은 실제 Connect·Debezium 과 같게(String·double·boolean·long) 둔다. */
    private record Fixed(Map<String, Object> attributes) implements DynamicMBean {
        @Override
        public Object getAttribute(String attribute) {
            return attributes.get(attribute);
        }

        @Override
        public AttributeList getAttributes(String[] names) {
            AttributeList list = new AttributeList();
            for (String n : names) {
                list.add(new Attribute(n, attributes.get(n)));
            }
            return list;
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            MBeanAttributeInfo[] infos = attributes.entrySet().stream()
                    .map(e -> new MBeanAttributeInfo(e.getKey(), e.getValue().getClass().getName(), e.getKey(),
                            true, false, false))
                    .toArray(MBeanAttributeInfo[]::new);
            return new MBeanInfo(Fixed.class.getName(), "fixed", infos, null, null, null);
        }

        @Override
        public void setAttribute(Attribute attribute) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) {
            throw new UnsupportedOperationException();
        }
    }
}
