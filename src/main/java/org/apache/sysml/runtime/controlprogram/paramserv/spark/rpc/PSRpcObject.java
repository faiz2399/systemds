/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.controlprogram.paramserv.spark.rpc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.controlprogram.paramserv.ParamservUtils;
import org.apache.sysml.runtime.instructions.cp.Data;
import org.apache.sysml.runtime.instructions.cp.ListObject;
import org.apache.sysml.runtime.io.IOUtilFunctions;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;

public abstract class PSRpcObject {

	public static final int PUSH = 1;
	public static final int PULL = 2;

	public abstract void deserialize(ByteBuffer buffer) throws IOException;

	public abstract ByteBuffer serialize() throws IOException;

	/**
	 * Deep serialize and write of a list object (currently only support list containing matrices)
	 * @param lo a list object containing only matrices
	 * @param dos output data to write to
	 */
	protected void serializeAndWriteListObject(ListObject lo, DataOutput dos) throws IOException {
		validateListObject(lo);
		dos.writeInt(lo.getLength()); //write list length
		dos.writeBoolean(lo.isNamedList()); //write list named
		for (int i = 0; i < lo.getLength(); i++) {
			if (lo.isNamedList())
				dos.writeUTF(lo.getName(i)); //write name
			((MatrixObject) lo.getData().get(i))
				.acquireReadAndRelease().write(dos); //write matrix
		}
	}
	
	protected ListObject readAndDeserialize(DataInput dis) throws IOException {
		int listLen = dis.readInt();
		List<Data> data = new ArrayList<>();
		List<String> names = dis.readBoolean() ?
			new ArrayList<>() : null;
		for(int i=0; i<listLen; i++) {
			if( names != null )
				names.add(dis.readUTF());
			MatrixBlock mb = new MatrixBlock();
			mb.readFields(dis);
			data.add(ParamservUtils.newMatrixObject(mb, false));
		}
		return new ListObject(data, names);
	}

	/**
	 * Get serialization size of a list object
	 * (scheme: size|name|size|matrix)
	 * @param lo list object
	 * @return serialization size
	 */
	protected int getExactSerializedSize(ListObject lo) {
		if( lo == null ) return 0;
		long result = 4 + 1; // list length and of named
		if (lo.isNamedList()) //size for names incl length
			result += lo.getNames().stream().mapToLong(s -> IOUtilFunctions.getUTFSize(s)).sum();
		result += lo.getData().stream().mapToLong(d ->
			((MatrixObject)d).acquireReadAndRelease().getExactSizeOnDisk()).sum();
		if( result > Integer.MAX_VALUE )
			throw new DMLRuntimeException("Serialized size ("+result+") larger than Integer.MAX_VALUE.");
		return (int) result;
	}

	private void validateListObject(ListObject lo) {
		for (Data d : lo.getData()) {
			if (!(d instanceof MatrixObject)) {
				throw new DMLRuntimeException(String.format("Paramserv func:"
					+ " Unsupported deep serialize of %s, which is not matrix.", d.getDebugName()));
			}
		}
	}
}
