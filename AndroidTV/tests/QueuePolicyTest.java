import com.fader.tv.QueuePolicy;
import java.util.Random;
public class QueuePolicyTest {
 public static void main(String[] args){
  Random rng=new Random(42);
  for(int count=2;count<100;count++)for(int i=0;i<count;i++){
   if(QueuePolicy.next(i,count,false,rng)!=(i+1)%count)throw new AssertionError("sequence");
   for(int j=0;j<100;j++){int n=QueuePolicy.next(i,count,true,rng);if(n==i||n<0||n>=count)throw new AssertionError("shuffle");}
  }
  if(QueuePolicy.next(0,1,true,rng)!=0)throw new AssertionError("single");
  if(QueuePolicy.fade(-100,3000)!=0 || QueuePolicy.fade(1500,3000)!=.5f || QueuePolicy.fade(4000,3000)!=1)throw new AssertionError("fade");
  System.out.println("PASS: sequential order, shuffle without immediate repeats, fade boundaries");
 }
}
