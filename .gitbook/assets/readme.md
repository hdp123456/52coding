	
#####參考链接：https://www.tmall.com/，https://blog.csdn.net
######建议拷贝到文档编译器查看
Client:									Server:
	TCP
		->SYN
								  SYN,ACK<-
		->ACK
	TSL
		->Client hello	
		(发送Client Hello并将Client支持的对称加密算法，密匙加密算法，验签算法发送给Server)
		
							  Server hello<-
							  (发送Server Hello并将Server中有的Client方支持的加密算法的一个发送给Client)
							  
							   Certificate<-
							   (发送Server端的证书，public key，Server Hello response)
							   
		->Client Key Exchange,Change Cipher Spec, Hello Request, Hello Request
		(发送客户端public key，确认交换加密算法)
		
		->Application Data
		(使用客户端的public key对Server端的public key进行加密并发送)
								
			New Session Ticket, Change Cipher Spec, Encrypted Handshake Message<-
			(创建新的TSL层的session ticket，确认交换加密算法，并发送加密警告)
			
						  Application Data<-
						  (使用Server端的public key对Client端的public key进行加密并发送)
						  
						   Encrypted Alert<-
						   (加密过程结束警告)
						
						   
	https://www.baidu.com/
	
Client:										Server:
	TCP
		->SYN
								  SYN,ACK<-
		->ACK
	TSL
		->Client Hello 
		(发送Client Hello并将Client支持的对称加密算法，密匙加密算法，验签算法发送给Server)
					
		  Server Hello, Change Cipher Spec<-
		  (发送Server Hello对Client Hello的响应，并告诉客户端接下来将发送交换密匙信息)
		
						  Application Data<-
						  (将Server端的证书，public key，以及使用的加密算法发送给Client)
						  
		->Change Cipher Spec, Application Data
		(响应Server[Change Cipher]成功，并使用Server端的public key对Client端的public key进行加密并发送)
		
		Application Data, Application Data<-
		(使用Client端的public key对Server端的public key进行加密并发送)
		
		
		
		
		
		
		
		