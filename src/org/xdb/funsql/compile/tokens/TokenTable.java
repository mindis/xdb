package org.xdb.funsql.compile.tokens;

import org.xdb.Config;

public class TokenTable extends AbstractToken{
	private static final long serialVersionUID = 8546480910845519464L;
	
	//attributes
	private TokenIdentifier name;
	private TokenSchema schema;
	private boolean isReference;
	
	//constructors
	public TokenTable(String name){
		this();
		this.name = new TokenIdentifier(name);
	}
	
	public TokenTable(){
		this.schema = new TokenSchema(Config.COMPILE_DEFAULT_SCHEMA);
	}
	
	//getters and setters
	public void setSchema(TokenSchema schema) {
		this.schema = schema;
	}
	
	public void setSchema(String schema) {
		this.schema = new TokenSchema(schema);
	}
	
	public void setName(String name) {
		this.name = new TokenIdentifier(name);
	}
	
	public void setName(TokenIdentifier name) {
		this.name = name;
	}
	
	public TokenIdentifier getName() {
		return name;
	}

	public TokenSchema getSchema() {
		return schema;
	}
	
	public boolean isReference() {
		return isReference;
	}

	public void setReference(boolean isReference) {
		this.isReference = isReference;
	}

	//helper methods
	@Override
	public String toString(){
		return this.toSqlString();
	}
	
	@Override
	public String toSqlString(){
		StringBuffer buffer =  new StringBuffer();
		if(this.schema != null){
			buffer.append(this.schema.toString());
			buffer.append(AbstractToken.DOT);
		}
		buffer.append(this.name.toString());
		
		return buffer.toString();
	}
	
	public String hashKey(long schemaOid){
		StringBuffer key = new StringBuffer();
		key.append(schemaOid);
		key.append(DOT);
		key.append(this.name.toString());
		return key.toString();
	}

}
