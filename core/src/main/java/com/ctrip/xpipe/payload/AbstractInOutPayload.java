package com.ctrip.xpipe.payload;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import com.ctrip.xpipe.api.payload.InOutPayload;

import io.netty.buffer.ByteBuf;

/**
 * @author wenchao.meng
 *
 * 2016年3月29日 下午4:31:30
 */
public abstract class AbstractInOutPayload implements InOutPayload{
	
	protected Logger logger = LoggerFactory.getLogger(getClass());

	private AtomicLong inSize = new AtomicLong();
	private AtomicLong outSize = new AtomicLong();
	
	@Override
	public void startInput() {
		inSize.set(0);
		doStartInput();
		
	}
	
	
	protected void doStartInput(){}


	@Override
	public long inputSize() {
		return inSize.get();
	}
	
	@Override
	public int in(ByteBuf byteBuf) throws IOException {
		
		int size = doIn(byteBuf);
		inSize.addAndGet(size);
		return size;
	}

	protected abstract int doIn(ByteBuf byteBuf) throws IOException;
	
	@Override
	public void endInput() {
		doEndInput();
	}

	protected void doEndInput(){}

	@Override
	public void startOutput() throws IOException {
		doStartOutput();
		outSize.set(0);
	}

	protected void doStartOutput() throws IOException{}
	

	@Override
	public long outSize() {
		return outSize.get();
	}


	@Override
	public long out(WritableByteChannel writableByteChannel) throws IOException {
		long size = doOut(writableByteChannel);
		outSize.addAndGet(size);
		return size;
	}

	protected abstract long doOut(WritableByteChannel writableByteChannel) throws IOException;
	
	@Override
	public void endOutput() {
		doEndOutput();
	}

	protected  void doEndOutput() {
	}
	
}
