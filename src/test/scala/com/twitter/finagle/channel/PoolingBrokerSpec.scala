package com.twitter.finagle.channel

import org.specs.Specification
import org.specs.mock.Mockito
import org.jboss.netty.channel._

import com.twitter.finagle.util.Conversions._

class PoolingBrokerSpec extends Specification with Mockito {
  "PoolingBroker" should {
    val someMessage = mock[Object]
    val reservedPipeline = Channels.pipeline()
    reservedPipeline.addLast("silenceWarnings", new SimpleChannelUpstreamHandler)
    val reservedChannel = new BrokeredChannelFactory().newChannel(reservedPipeline)

    val pool = mock[ChannelPool]
    val reservationFuture = Channels.future(reservedChannel)
    pool.reserve() returns(reservationFuture)
    val poolingPipeline = Channels.pipeline()
    poolingPipeline.addLast("silenceWarnings", new SimpleChannelUpstreamHandler)
    val poolingChannel = new BrokeredChannelFactory().newChannel(poolingPipeline)
    val poolingBroker = new PoolingBroker(pool)
    poolingChannel.connect(poolingBroker)

    "when response is cancelled prior to getting connection" in {
      var reservedDispatchWasInvoked = false

      reservedChannel.connect(new Broker {
        def dispatch(e: MessageEvent) = {
          reservedDispatchWasInvoked = true
          null
        }
      })

      val future = Channels.future(poolingChannel)

      val responseEvent = poolingBroker.dispatch(
        new DownstreamMessageEvent(poolingChannel, future, "something", null))

      responseEvent.cancel()
      reservationFuture.setSuccess()

      reservedDispatchWasInvoked must beFalse

      there was one(pool).reserve()
      there was one(pool).release(reservedChannel)
    }

    "when the reservation is successful" in {
      reservationFuture.setSuccess()
      "when the message is sent successfully" in {
        "dispatch reserves and releases connection from the pool" in {
          val responseEvent = new UpcomingMessageEvent(poolingChannel)

          reservedChannel.connect(new Broker {
            def dispatch(e: MessageEvent) = {
              e.getFuture.setSuccess()
              responseEvent
            }
          })

          Channels.write(poolingChannel, someMessage)
          there was one(pool).reserve()
          there was no(pool).release(reservedChannel)

          responseEvent.setFinalMessage("something")
          there was one(pool).release(reservedChannel)
        }


        "the response is forwarded back to the poolingChannel" in {
          var messageReceivedWasCalled = false
          reservedChannel.connect(new Broker {
            def dispatch(e: MessageEvent) = {
              e.getFuture.setSuccess()
              UpcomingMessageEvent.successfulEvent(e.getChannel, someMessage)
            }
          })
          poolingChannel.getPipeline.addLast("handler", new SimpleChannelUpstreamHandler {
            override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
              messageReceivedWasCalled = true
            }
          })

          Channels.write(poolingChannel, someMessage)
          messageReceivedWasCalled mustBe true
        }
      }

      "when the message is sent unsuccessfully" in {
        reservedChannel.connect(new Broker {
          def dispatch(e: MessageEvent) = {
            val exc = new Exception
            e.getFuture.setFailure(exc)
            UpcomingMessageEvent.failedEvent(e.getChannel, exc)
          }
        })

        "dispatch reserves and releases connection from the pool" in {
          var exceptionCaughtWasCalled = false
          poolingChannel.getPipeline.addLast("handler", new SimpleChannelUpstreamHandler {
            override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
              exceptionCaughtWasCalled = true
            }
          })

          Channels.write(poolingChannel, someMessage)

          there was one(pool).reserve()
          there was one(pool).release(reservedChannel)

          exceptionCaughtWasCalled mustBe true
        }

        "the exceptionCaught callback is invoked" in {
          var exceptionCaughtWasCalled = false
          poolingChannel.getPipeline.addLast("handler", new SimpleChannelUpstreamHandler {
            override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
              exceptionCaughtWasCalled = true
            }
          })
          Channels.write(poolingChannel, someMessage)
          exceptionCaughtWasCalled mustBe true
        }
      }
    }

    "when the reservation is unsuccessful" in {
      reservationFuture.setFailure(new Exception)

      "the exception caught callback is fired" in {
        var exceptionCaughtWasCalled = false
        poolingChannel.getPipeline.addLast("handler", new SimpleChannelUpstreamHandler {
          override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
            exceptionCaughtWasCalled = true
          }
        })
        Channels.write(poolingChannel, someMessage)
        exceptionCaughtWasCalled mustBe true
      }
    }
  }
}