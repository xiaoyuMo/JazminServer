/**
 * 
 */
package jazmin.driver.mq.file;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;

import jazmin.driver.mq.Message;
import jazmin.driver.mq.MessageQueueDriver;
import jazmin.driver.mq.TopicQueue;
import jazmin.log.Logger;
import jazmin.log.LoggerFactory;

/**
 * @author yama
 *
 */
public class FileTopicQueue extends TopicQueue{
	private static Logger logger=LoggerFactory.get(FileTopicQueue.class);
	//
	private String workDir;
	private int indexFileCapacity;
	File workDirFile;
	
	LinkedList<IndexFile>indexSegmentFiles;
	LinkedList<IndexFile>takeIndexSegmentFiles;
	private int currentIndex=0;
	
	//
	//
	public FileTopicQueue(String id) {
		super(id, MessageQueueDriver.TOPIC_QUEUE_TYPE_FILE);
		indexFileCapacity=100000;
		maxTtl=1000*60*60;//1 hour
		redelieverInterval=1000*5;//5 seconds redeliever 
		topicSubscribers=new TreeSet<>();
		indexSegmentFiles=new LinkedList<IndexFile>();
		takeIndexSegmentFiles=new LinkedList<IndexFile>();
	}
	//
	
	//
	@Override
	public void start() {
		super.start();
		workDirFile=new File(workDir,id);
		if(!workDirFile.exists()){
			boolean r=workDirFile.mkdirs();
			if(!r){
				throw new IllegalArgumentException("can not create work dir:"+workDirFile.getAbsolutePath());
			}
			logger.info("create work dir:{}",workDirFile.getAbsoluteFile());
		}
		//
		File files[]=workDirFile.listFiles((dir,name)->{
			return name.endsWith(".index")&&name.startsWith(id+"-");
		});
	
		for(File f:files){
			logger.info("load index file:{}",f.getAbsoluteFile());
			IndexFile file=IndexFile.get(f.getAbsolutePath(),indexFileCapacity);
			if(file!=null){
				indexSegmentFiles.add(file);
				takeIndexSegmentFiles.add(file);
			}
		}
	}
	//
	public void stop(){
		synchronized (lockObject) {
			for(IndexFile file:indexSegmentFiles){
				file.close();
			}
		}
	}
	//
	public List<IndexFile>getIndexSegmentFiles(){
		synchronized (lockObject) {
			return new ArrayList<IndexFile>(indexSegmentFiles);
		}
	}
	public List<IndexFile>getTakeSegmentFiles(){
		synchronized (lockObject) {
			return new ArrayList<IndexFile>(takeIndexSegmentFiles);
		}
	}
	//
	/**
	 * @return the workDir
	 */
	public String getWorkDir() {
		return workDir;
	}

	/**
	 * @param workDir the workDir to set
	 */
	public void setWorkDir(String workDir) {
		this.workDir = workDir;
	}
	//
	@Override
	public int length() {
		synchronized (lockObject) {
			int total=0;
			for(IndexFile file:indexSegmentFiles){
				total+=file.size();
			}
			return total;
		}
	}

	@Override
	public void publish(Object obj) {
		super.publish(obj);
		//
		IndexFile currentIndexFile;
		synchronized (lockObject) {
			if(indexSegmentFiles.isEmpty()){
				currentIndexFile=newIndexFile(0);
				indexSegmentFiles.add(currentIndexFile);
				takeIndexSegmentFiles.add(currentIndexFile);
			}
			currentIndexFile=indexSegmentFiles.getLast();
			//
			DataItem item=getDataItem(obj);
			long currentOffset=0;
			for(short subscriber :topicSubscribers){
				if(currentIndexFile.space()<=0){
					currentIndexFile.flush();
					//full add new index
					int nextIndex=(currentIndexFile.index+1);
					currentIndexFile=newIndexFile(nextIndex);
					indexSegmentFiles.add(currentIndexFile);
					takeIndexSegmentFiles.add(currentIndexFile);
					//new file save data
					currentOffset=currentIndexFile.dataFile.append(item);
				}
				if(currentOffset==0){
					currentOffset=currentIndexFile.dataFile.append(item);
				}
				IndexFileItem idxItem=new IndexFileItem();
				idxItem.dataOffset=currentOffset;
				idxItem.flag=IndexFileItem.FLAG_READY;
				idxItem.magic=IndexFileItem.MAGIC;
				idxItem.subscriber=subscriber;
				currentIndexFile.addItem(idxItem);
			}
		}
	}
	//
	private IndexFile newIndexFile(int index){
		File newIdxFile=new File(workDirFile.getAbsoluteFile(),id+"-"+index+".index");
		IndexFile file=new IndexFile(newIdxFile.getAbsolutePath(), indexFileCapacity);
		file.index=index;
		file.open();
		return file;
	}
	//
	private DataItem getDataItem(Object obj){
		DataItem item=new DataItem();
		if(!(obj instanceof byte[])){
			throw new IllegalArgumentException("payload type must be byte[]");
		}
		item.payloadType=DataItem.PAYLOAD_TYPE_RAW;
		item.payload=(byte[])obj;
		if(item.payload.length>Short.MAX_VALUE){
			throw new IllegalArgumentException("payload length should less than "
					+Short.MAX_VALUE+" but "+item.payload.length);
		}
		item.payloadLength=(short) item.payload.length;
		item.magic=DataItem.MAGIC;
		return item;
	}
	//
	@Override
	public void reject(long id) {
		synchronized (lockObject) {
			updateFlag(id,IndexFileItem.FLAG_REJECTED);
		}
	
	}
	//
	@Override
	public void accept(long id) {
		synchronized (lockObject) {
			updateFlag(id,IndexFileItem.FLAG_ACCEPTED);
		}
	}
	//
	private void updateFlag(long id,byte flag){
		int indexSegmentId=(int) (id>>32);
		int offsetId=(int) (id&0x00000000FFFFFFFF);
		for(IndexFile file : indexSegmentFiles){
			if(file.index==indexSegmentId){
				file.updateFlag(offsetId, flag);
			}
		}
	}
	//
	@Override
	public Message take() {
		synchronized (lockObject) {
			if(takeIndexSegmentFiles.isEmpty()){
				return null;
			}
			Message retMessage=null;
			IndexFile indexFile=takeIndexSegmentFiles.getFirst();
			if(currentIndex==0){
				//reset remove count
				indexFile.removedCount=0;
			}
			IndexFileItem item=indexFile.getItem(currentIndex);
			if(item.flag==IndexFileItem.FLAG_ACCEPTED||item.flag==IndexFileItem.FLAG_EXPRIED){
				indexFile.removedCount++;
			}
			//
			if(item.lastDelieverTime>0&&item.flag!=IndexFileItem.FLAG_EXPRIED){
				if((System.currentTimeMillis()-item.lastDelieverTime)>maxTtl){
					//max ttl 
					item.flag=IndexFileItem.FLAG_EXPRIED;
					indexFile.updateFlag(currentIndex, item.flag);
					expriedCount.increment();
					retMessage=MessageQueueDriver.takeNext;
				}	
			}
			//
			if((System.currentTimeMillis()-item.lastDelieverTime)<redelieverInterval){
				retMessage=MessageQueueDriver.takeNext;
			}
			//
			if(retMessage!=MessageQueueDriver.takeNext){
				if(item.flag==IndexFileItem.FLAG_READY){
					retMessage=getMessage(indexFile,item);
					item.delieverTimes++;
					indexFile.updateLastDelieverTime(currentIndex,
							System.currentTimeMillis(),(short) (item.delieverTimes));
					retMessage.delieverTimes=item.delieverTimes;
				}
				if(item.flag==IndexFileItem.FLAG_REJECTED){
					retMessage=getMessage(indexFile,item);
					item.delieverTimes++;
					indexFile.updateLastDelieverTime(currentIndex,
							System.currentTimeMillis(),(short) (item.delieverTimes));
					retMessage.delieverTimes=item.delieverTimes;
				}
				if(item.flag==IndexFileItem.FLAG_ACCEPTED
						||item.flag==IndexFileItem.FLAG_EXPRIED){
					retMessage=MessageQueueDriver.takeNext;
				}
			}
			
			//
			currentIndex++;
			if(currentIndex>=indexFileCapacity){
				currentIndex=0;
				if(indexFile.removedCount>=indexFileCapacity){
					indexFile.delete();
					takeIndexSegmentFiles.removeFirst();
					indexSegmentFiles.remove(indexFile);
					logger.info("delete index file {}",indexFile.indexFile.getAbsoluteFile());
				}else{
					IndexFile head=takeIndexSegmentFiles.removeFirst();
					takeIndexSegmentFiles.add(head);
				}
			}
			//
			if(retMessage==null){
				//rewind
				currentIndex=0;
				if(indexFile!=null){
					indexFile.removedCount=0;
				}
				if(takeIndexSegmentFiles.size()>1){
					IndexFile head=takeIndexSegmentFiles.removeFirst();
					takeIndexSegmentFiles.add(head);
				}	
			}
			return retMessage;
		}
	}
	//
	private Message getMessage(IndexFile indexFile,IndexFileItem item){
		Message message=new Message();
		message.id=item.uuid;
		message.subscriber=item.subscriber;
		DataItem data=indexFile.dataFile.get(item.dataOffset);
		if(data.payloadType==DataItem.PAYLOAD_TYPE_RAW){
			message.payload=data.payload;
		}
		message.payload=data.payload;
		message.delieverTimes=item.delieverTimes;
		return message;
	}
	

}